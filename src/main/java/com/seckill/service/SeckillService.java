package com.seckill.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.seckill.cache.HotKeyDetector;
import com.seckill.config.RabbitMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.dto.SeckillResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀核心服务
 *
 * 面试可讲：
 * - 三层漏斗：Bloom Filter预筛 → 资格校验Lua → 库存扣减Lua
 * - MQ后置：只有扣库存成功的用户才发消息，保护MQ不被无效请求淹没
 * - 双Lua脚本分工：高频扣库存脚本<3ms，资格校验脚本<5ms
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final AllocationService allocationService;
    private final HotKeyDetector hotKeyDetector;

    private final DefaultRedisScript<Long> checkQualifyScript;

    private BloomFilter<String> bloomFilter;

    @Value("${seckill.bloom-filter.expected-insertions:1000000}")
    private int expectedInsertions;

    @Value("${seckill.bloom-filter.fpp:0.01}")
    private double fpp;

    @PostConstruct
    public void initBloomFilter() {
        bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                fpp
        );
        log.info("Bloom Filter 初始化完成: expectedInsertions={}, fpp={}", expectedInsertions, fpp);
    }

    /**
     * 执行秒杀
     */
    public SeckillResponse executeSeckill(Long userId, Long couponId) {
        String userBloomKey = userId + ":" + couponId;
        long startTime = System.currentTimeMillis();

        // ──── 第一层：Bloom Filter 预筛 ────
        if (bloomFilter.mightContain(userBloomKey)) {
            log.debug("Bloom Filter 命中学: userId={}, couponId={}", userId, couponId);
            // 可能存在 → 精确查 Redis Set
            String userSetKey = "seckill:user:" + couponId;
            Boolean exists = redisTemplate.opsForSet().isMember(userSetKey, userId.toString());
            if (Boolean.TRUE.equals(exists)) {
                return SeckillResponse.fail("您已参与过本次活动");
            }
        }

        // ──── 第二层：智能分配权重计算 ────
        int weight = allocationService.calculateFinalWeight(userId, couponId);
        if (weight <= 0) {
            return SeckillResponse.fail("活动太火爆了，请稍后再试");
        }

        // ──── 第三层：资格校验 + 扣库存 + 一人一单原子 Lua ────
        String couponKey = "seckill:coupon:" + couponId;
        // 记录热点访问（每次秒杀请求都上报到环形缓冲区）
        hotKeyDetector.record(couponKey);
        String userSetKey = "seckill:user:" + couponId;
        long currentTime = Instant.now().toEpochMilli();

        Long qualifyResult = redisTemplate.execute(
                checkQualifyScript,
                List.of(couponKey, userSetKey),
                userId.toString(), String.valueOf(currentTime), "1"
        );

        if (qualifyResult == null || qualifyResult < 0) {
            int result = qualifyResult == null ? -99 : qualifyResult.intValue();
            String reason = switch (result) {
                case -1 -> "活动未开始或已结束";
                case -2 -> "您已参与过本次活动";
                case -3 -> "优惠券已抢光";
                case -4 -> "操作过于频繁";
                default -> "未知错误";
            };
            return SeckillResponse.fail(reason);
        }

        // Lua 返回扣减后的剩余库存，成功后才生成订单并投递 MQ。
        String orderNo = generateOrderNo();
        // ──── 第四层：更新进程内 Bloom Filter（仅作性能预筛） ────
        bloomFilter.put(userBloomKey);

        // ──── 异步发MQ创建订单 ────
        OrderMessage message = OrderMessage.builder()
                .orderNo(orderNo)
                .userId(userId)
                .couponId(couponId)
                .amount(BigDecimal.ZERO)
                .userWeight(weight)
                .timestamp(System.currentTimeMillis())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_CREATE_EXCHANGE,
                RabbitMQConfig.ORDER_CREATE_KEY,
                message
        );

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("秒杀成功: userId={}, couponId={}, orderNo={}, weight={}, elapsed={}ms",
                userId, couponId, orderNo, weight, elapsed);

        return SeckillResponse.ok(orderNo, weight);
    }

    private String generateOrderNo() {
        return Instant.now().toEpochMilli() + UUID.randomUUID().toString().substring(0, 8);
    }
}

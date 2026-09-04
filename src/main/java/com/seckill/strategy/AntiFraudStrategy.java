package com.seckill.strategy;

import com.seckill.util.DeviceFingerprintUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 防黄牛策略：设备指纹 + 滑动窗口频次检测
 *
 * 设计说明：
 * - 设备指纹：Canvas Fingerprint → SHA256，不依赖 Cookie，清缓存也无法绕过
 * - 滑动窗口：Redis ZSET score=时间戳，ZREMRANGEBYSCORE + ZCARD，
 *   避免固定窗口的"边界双倍问题"
 * - 频次异常直接降权至0（拒绝参与秒杀）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AntiFraudStrategy implements AllocationStrategy {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${seckill.anti-fraud.window-seconds:60}")
    private int windowSeconds;

    @Value("${seckill.anti-fraud.max-requests-per-window:10}")
    private int maxRequestsPerWindow;

    @Override
    public int calculateWeight(Long userId, Long couponId) {
        // 这里 userId 实际传入的是通过 ThreadLocal 获取的真实 userId
        // 设备指纹从请求上下文中获取
        String deviceFingerprint = DeviceFingerprintUtil.getCurrentDeviceFingerprint();
        if (deviceFingerprint == null) {
            return 0; // 无法获取设备指纹，不惩罚也不加分
        }

        String zsetKey = "device:access:" + deviceFingerprint;
        long now = Instant.now().toEpochMilli();
        long windowStart = now - Duration.ofSeconds(windowSeconds).toMillis();

        // 清理窗口外的旧记录
        redisTemplate.opsForZSet().removeRangeByScore(zsetKey, 0, windowStart);

        // 统计窗口内请求次数
        Long count = redisTemplate.opsForZSet().zCard(zsetKey);
        long requestCount = count != null ? count : 0;

        // 记录本次访问
        redisTemplate.opsForZSet().add(zsetKey, String.valueOf(now), now);
        redisTemplate.expire(zsetKey, Duration.ofSeconds(windowSeconds * 2));

        if (requestCount >= maxRequestsPerWindow) {
            log.warn("黄牛检测: userId={}, deviceFingerprint={}, count={}",
                    userId, deviceFingerprint, requestCount);
            return Integer.MIN_VALUE; // 直接拒绝
        }

        return 0;
    }

    @Override
    public int getPriority() {
        return 0; // 最高优先级，先执行防黄牛检查
    }

    @Override
    public String getName() {
        return "防黄牛策略";
    }
}

package com.seckill.scheduler;

import com.seckill.config.RabbitMQConfig;
import com.seckill.dto.OrderMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.redisson.api.RedissonClient;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.service.NotificationService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Replays order messages persisted by the seckill Lua script. The Lua script
 * writes this record in the same Redis atomic operation as the stock
 * deduction, closing the process-crash gap before RabbitMQ publication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderRecoveryScheduler {

    public static final String PENDING_ORDER_INDEX = "seckill:pending:orders";
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;
    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final NotificationService notificationService;

    @Value("${seckill.pending-order.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelay = 1000)
    public void replayPendingOrders() {
        var lock = redissonClient.getLock("lock:pending-order-recovery");
        if (!lock.tryLock()) return;
        try {
        long now = System.currentTimeMillis();
        Set<String> orderNos = stringRedisTemplate.opsForZSet()
                .rangeByScore(PENDING_ORDER_INDEX, 0, now, 0, BATCH_SIZE);
        if (orderNos == null || orderNos.isEmpty()) return;

        for (String orderNo : orderNos) {
            replay(orderNo, now);
        }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void replay(String orderNo, long now) {
        String key = pendingOrderKey(orderNo);
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        if (raw.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(PENDING_ORDER_INDEX, orderNo);
            return;
        }

        try {
            OrderMessage message = OrderMessage.builder()
                    .orderNo(value(raw, "order_no"))
                    .userId(Long.parseLong(value(raw, "user_id")))
                    .couponId(Long.parseLong(value(raw, "coupon_id")))
                    .amount(BigDecimal.ZERO)
                    .userWeight(Integer.parseInt(value(raw, "user_weight")))
                    .timestamp(Long.parseLong(value(raw, "timestamp")))
                    .build();
            CorrelationData correlation = new CorrelationData(orderNo);
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_CREATE_EXCHANGE,
                    RabbitMQConfig.ORDER_CREATE_KEY, message, correlation);
            var confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ 发布确认失败");
            }
            stringRedisTemplate.delete(key);
            stringRedisTemplate.opsForZSet().remove(PENDING_ORDER_INDEX, orderNo);
            log.info("已补偿投递秒杀订单: orderNo={}", orderNo);
        } catch (Exception e) {
            scheduleRetry(orderNo, key, raw, now, e);
        }
    }

    private void notifyTerminalFailure(String orderNo, Map<Object, Object> raw, int retryCount) {
        try {
            Long couponId = Long.valueOf(value(raw, "coupon_id"));
            couponRepository.findById(couponId).ifPresent(coupon ->
                    merchantRepository.findById(coupon.getMerchantId()).ifPresent(merchant ->
                            notificationService.notify(merchant.getUserId(), "MQ_COMPENSATION_FAILED",
                                    "抢券订单补偿失败", "订单 " + orderNo + " 已重试 " + retryCount
                                            + " 次，请联系运营人员人工恢复。")));
        } catch (Exception notificationError) {
            log.error("pending order terminal notification failed: orderNo={}", orderNo, notificationError);
        }
    }

    private void scheduleRetry(String orderNo, String key, Map<Object, Object> raw, long now, Exception error) {
        int retryCount = parseInt(value(raw, "retry_count")) + 1;
        if (retryCount >= maxRetries) {
            stringRedisTemplate.opsForHash().put(key, "state", "FAILED");
            stringRedisTemplate.opsForHash().put(key, "retry_count", String.valueOf(retryCount));
            stringRedisTemplate.opsForZSet().remove(PENDING_ORDER_INDEX, orderNo);
            notifyTerminalFailure(orderNo, raw, retryCount);
            log.error("秒杀订单补偿达到重试上限: orderNo={}, retryCount={}", orderNo, retryCount, error);
            return;
        }
        long delayMs = Math.min(1L << Math.min(retryCount - 1, 5), 30) * 1000L;
        stringRedisTemplate.opsForHash().put(key, "retry_count", String.valueOf(retryCount));
        stringRedisTemplate.opsForHash().put(key, "state", "PENDING");
        stringRedisTemplate.opsForZSet().add(PENDING_ORDER_INDEX, orderNo, now + delayMs);
        log.warn("秒杀订单补偿投递失败，将重试: orderNo={}, retryCount={}, delayMs={}",
                orderNo, retryCount, delayMs, error);
    }

    private static String value(Map<Object, Object> raw, String field) {
        Object value = raw.get(field);
        if (value == null) throw new IllegalStateException("pending order 缺少字段: " + field);
        return String.valueOf(value);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String pendingOrderKey(String orderNo) {
        return "seckill:pending:order:" + orderNo;
    }
}

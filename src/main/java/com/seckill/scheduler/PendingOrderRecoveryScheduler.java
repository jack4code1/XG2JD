package com.seckill.scheduler;

import com.seckill.config.RabbitMQConfig;
import com.seckill.constant.SeckillRedisKeys;
import com.seckill.dto.OrderMessage;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.service.NotificationService;
import com.seckill.service.PendingOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Replays only pending orders that have not received a publisher confirm. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderRecoveryScheduler {

    public static final String PENDING_ORDER_INDEX = SeckillRedisKeys.PENDING_ORDER_INDEX;
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;
    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final PendingOrderService pendingOrderService;

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
                reconcile(orderNo, now);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    void reconcile(String orderNo, long now) {
        String key = pendingOrderKey(orderNo);
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        if (raw.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(PENDING_ORDER_INDEX, orderNo);
            return;
        }

        OrderMessage message;
        try {
            message = message(raw);
        } catch (RuntimeException error) {
            log.error("pending order 数据不完整，保留等待人工处理: orderNo={}", orderNo, error);
            return;
        }

        String state = value(raw, "state");
        if (PendingOrderService.PUBLISHED.equals(state)) {
            reconcilePublished(orderNo, message, now);
            return;
        }
        if (!PendingOrderService.PUBLISHING.equals(state) && !PendingOrderService.RETRY_WAIT.equals(state)
                && !PendingOrderService.RECOVERING.equals(state)) {
            return;
        }
        if (!pendingOrderService.claimRecovery(orderNo, message.getCouponId(), now)) {
            return;
        }

        // A consumer may have committed while this scheduler was claiming the lease.
        if (orderRepository.findByOrderNo(orderNo).isPresent()) {
            pendingOrderService.acknowledge(orderNo, message.getCouponId());
            return;
        }
        publishRecovered(orderNo, key, raw, message, now);
    }

    private void reconcilePublished(String orderNo, OrderMessage message, long now) {
        if (orderRepository.findByOrderNo(orderNo).isPresent()) {
            pendingOrderService.acknowledge(orderNo, message.getCouponId());
            return;
        }
        // Publisher confirm means RabbitMQ owns a durable copy. Do not turn ordinary consumer lag
        // into a duplicate publish; retain pending and revisit it later for operational reconciliation.
        pendingOrderService.deferPublishedReconciliation(orderNo, message.getCouponId(), now);
        log.warn("已确认投递但尚未落库，保留 pending 观察: orderNo={}", orderNo);
    }

    private void publishRecovered(String orderNo, String key, Map<Object, Object> raw, OrderMessage message, long now) {
        try {
            CorrelationData correlation = new CorrelationData(orderNo);
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDER_CREATE_EXCHANGE,
                    RabbitMQConfig.ORDER_CREATE_KEY, message, correlation);
            var confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ 发布确认失败");
            }
            pendingOrderService.markDeliveryConfirmed(orderNo, message.getCouponId());
            log.info("pending 订单补偿投递已确认: orderNo={}", orderNo);
        } catch (Exception error) {
            scheduleRetry(orderNo, key, raw, message.getCouponId(), now, error);
        }
    }

    private void scheduleRetry(String orderNo, String key, Map<Object, Object> raw, Long couponId, long now, Exception error) {
        int previousRetryCount = parseInt(value(raw, "retry_count"));
        long delayMs = Math.min(1L << Math.min(previousRetryCount, 5), 30) * 1000L;
        long result = pendingOrderService.retryLater(orderNo, couponId, now, delayMs, maxRetries);
        if (result < 0) {
            notifyTerminalFailure(orderNo, raw, (int) -result);
            log.error("秒杀订单补偿达到重试上限: orderNo={}, retryCount={}", orderNo, -result, error);
            return;
        }
        if (result > 0) {
            log.warn("秒杀订单补偿投递失败，将重试: orderNo={}, retryCount={}, delayMs={}",
                    orderNo, result, delayMs, error);
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

    private static OrderMessage message(Map<Object, Object> raw) {
        return OrderMessage.builder()
                .orderNo(value(raw, "order_no"))
                .userId(Long.parseLong(value(raw, "user_id")))
                .couponId(Long.parseLong(value(raw, "coupon_id")))
                .amount(BigDecimal.ZERO)
                .userWeight(Integer.parseInt(value(raw, "user_weight")))
                .timestamp(Long.parseLong(value(raw, "timestamp")))
                .build();
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
        return SeckillRedisKeys.pendingOrder(orderNo);
    }
}

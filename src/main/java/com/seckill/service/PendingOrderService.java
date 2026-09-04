package com.seckill.service;

import com.seckill.constant.SeckillRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Coordinates pending-order lifecycle transitions atomically in Redis. */
@Service
@RequiredArgsConstructor
public class PendingOrderService {
    public static final String PUBLISHING = "PUBLISHING";
    public static final String RECOVERING = "RECOVERING";
    public static final String RETRY_WAIT = "RETRY_WAIT";
    public static final String PUBLISHED = "PUBLISHED";

    private final StringRedisTemplate stringRedisTemplate;
    @Qualifier("pendingOrderTransitionScript")
    private final DefaultRedisScript<Long> pendingOrderTransitionScript;

    @Value("${seckill.pending-order.recovery-lease-ms:5000}")
    private long recoveryLeaseMs;

    @Value("${seckill.pending-order.published-reconcile-ms:30000}")
    private long publishedReconcileMs;

    @Value("${seckill.pending-order.max-retries:10}")
    private int maxRetries;

    /**
     * A confirm received after the consumer committed is intentionally a no-op:
     * the Lua transition never recreates a pending hash removed by ACK.
     */
    public boolean markDeliveryConfirmed(String orderNo, Long couponId) {
        long now = System.currentTimeMillis();
        return transition("CONFIRM_PUBLISHED", orderNo, couponId, now, now + publishedReconcileMs, 0) == 1;
    }

    /**
     * A broker return means no queue accepted the message. Keep the Redis
     * acceptance record and schedule the existing recovery worker to retry.
     */
    public boolean markDeliveryReturned(String orderNo) {
        Object couponIdValue = stringRedisTemplate.opsForHash()
                .get(SeckillRedisKeys.pendingOrder(orderNo), "coupon_id");
        if (couponIdValue == null) return false;
        try {
            long now = System.currentTimeMillis();
            return transition("RETURNED", orderNo, Long.parseLong(couponIdValue.toString()),
                    now, now + 1000, maxRetries) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public void acknowledgeAfterCommit(String orderNo, Long couponId) {
        Runnable acknowledge = () -> acknowledge(orderNo, couponId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    acknowledge.run();
                }
            });
            return;
        }
        acknowledge.run();
    }

    public void acknowledge(String orderNo, Long couponId) {
        transition("ACK", orderNo, couponId, System.currentTimeMillis(), 0, 0);
    }

    /** Atomically grants one recovery worker the right to republish an overdue unconfirmed order. */
    public boolean claimRecovery(String orderNo, Long couponId, long now) {
        return transition("CLAIM_RECOVERY", orderNo, couponId, now, now + recoveryLeaseMs, 0) == 1;
    }

    /** Schedules the next recovery attempt and returns its retry count; a negative value is terminal. */
    public long retryLater(String orderNo, Long couponId, long now, long delayMs, int maxRetries) {
        return transition("RETRY_LATER", orderNo, couponId, now, now + delayMs, maxRetries);
    }

    /** A confirmed message belongs to RabbitMQ; reconcile it without publishing a second copy. */
    public void deferPublishedReconciliation(String orderNo, Long couponId, long now) {
        transition("DEFER_PUBLISHED", orderNo, couponId, now, now + publishedReconcileMs, 0);
    }

    private long transition(String action, String orderNo, Long couponId, long now, long nextDue, int maxRetries) {
        Long result = stringRedisTemplate.execute(pendingOrderTransitionScript,
                List.of(SeckillRedisKeys.pendingOrder(orderNo), SeckillRedisKeys.PENDING_ORDER_INDEX,
                        SeckillRedisKeys.pending(couponId)),
                action, orderNo, String.valueOf(now), String.valueOf(nextDue), String.valueOf(maxRetries));
        return result == null ? 0 : result;
    }
}

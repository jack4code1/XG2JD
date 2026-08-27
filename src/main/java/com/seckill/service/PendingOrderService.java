package com.seckill.service;

import com.seckill.constant.SeckillRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Confirms a Redis-accepted order only after its database transaction commits. */
@Service
@RequiredArgsConstructor
public class PendingOrderService {
    private static final long CONSUMER_CONFIRM_DELAY_MS = 5_000L;
    private final StringRedisTemplate stringRedisTemplate;

    public void markDeliveryConfirmed(String orderNo, Long couponId) {
        String key = SeckillRedisKeys.pendingOrder(orderNo);
        stringRedisTemplate.opsForHash().put(key, "state", "DELIVERED");
        stringRedisTemplate.opsForZSet().add(SeckillRedisKeys.PENDING_ORDER_INDEX, orderNo,
                System.currentTimeMillis() + CONSUMER_CONFIRM_DELAY_MS);
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
        stringRedisTemplate.delete(SeckillRedisKeys.pendingOrder(orderNo));
        stringRedisTemplate.opsForZSet().remove(SeckillRedisKeys.PENDING_ORDER_INDEX, orderNo);
        stringRedisTemplate.opsForList().remove(SeckillRedisKeys.pending(couponId), 1, orderNo);
    }
}

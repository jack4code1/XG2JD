package com.seckill.scheduler;

import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.service.NotificationService;
import com.seckill.service.PendingOrderService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderRecoverySchedulerTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishedButNotYetClearedOrderIsReconciledWithoutRepublishing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<Object, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hashes);
        when(hashes.entries(PendingOrderRecoveryScheduler.pendingOrderKey("o-1"))).thenReturn(pending("PUBLISHED"));
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByOrderNo("o-1")).thenReturn(Optional.empty());
        PendingOrderService pending = mock(PendingOrderService.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

        scheduler(redis, rabbit, orders, pending).reconcile("o-1", 100L);

        verify(pending).deferPublishedReconciliation("o-1", 8L, 100L);
        verify(rabbit, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), any(Object.class),
                org.mockito.ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentInitialPublisherOrOtherRecoveryLeasePreventsSecondPublish() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<Object, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn((HashOperations) hashes);
        when(hashes.entries(PendingOrderRecoveryScheduler.pendingOrderKey("o-2"))).thenReturn(pending("PUBLISHING"));
        PendingOrderService pending = mock(PendingOrderService.class);
        when(pending.claimRecovery("o-2", 8L, 100L)).thenReturn(false);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);

        scheduler(redis, rabbit, mock(OrderRepository.class), pending).reconcile("o-2", 100L);

        verify(rabbit, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), any(Object.class),
                org.mockito.ArgumentMatchers.any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    }

    private PendingOrderRecoveryScheduler scheduler(StringRedisTemplate redis, RabbitTemplate rabbit,
                                                     OrderRepository orders, PendingOrderService pending) {
        return new PendingOrderRecoveryScheduler(redis, rabbit, mock(RedissonClient.class), mock(CouponRepository.class),
                mock(MerchantRepository.class), mock(NotificationService.class), orders, pending);
    }

    private Map<Object, Object> pending(String state) {
        return Map.of("state", state, "order_no", state.equals("PUBLISHED") ? "o-1" : "o-2",
                "user_id", "7", "coupon_id", "8", "user_weight", "1", "timestamp", "1", "retry_count", "0");
    }
}

package com.seckill.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingOrderServiceTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesPendingOnlyAfterRegisteredTransactionCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        when(redis.opsForList()).thenReturn(mock(ListOperations.class));
        DefaultRedisScript<Long> transition = mock(DefaultRedisScript.class);
        PendingOrderService service = new PendingOrderService(redis, transition);
        ReflectionTestUtils.setField(service, "publishedReconcileMs", 30_000L);
        TransactionSynchronizationManager.initSynchronization();

        service.acknowledgeAfterCommit("order-1", 6L);

        verify(redis, never()).execute(org.mockito.ArgumentMatchers.eq(transition), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(redis).execute(org.mockito.ArgumentMatchers.eq(transition), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void latePublisherConfirmAfterConsumerAcknowledgementDoesNotRecreatePending() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DefaultRedisScript<Long> transition = mock(DefaultRedisScript.class);
        when(redis.execute(org.mockito.ArgumentMatchers.eq(transition), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(0L);
        PendingOrderService service = new PendingOrderService(redis, transition);
        ReflectionTestUtils.setField(service, "publishedReconcileMs", 30_000L);

        boolean changed = service.markDeliveryConfirmed("order-2", 6L);

        org.junit.jupiter.api.Assertions.assertFalse(changed);
        verify(redis, never()).opsForHash();
        verify(redis, never()).opsForZSet();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyOneRecoveryWorkerCanClaimTheSamePendingOrder() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DefaultRedisScript<Long> transition = mock(DefaultRedisScript.class);
        when(redis.execute(org.mockito.ArgumentMatchers.eq(transition), org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(Object[].class))).thenReturn(1L, 0L);
        PendingOrderService service = new PendingOrderService(redis, transition);
        ReflectionTestUtils.setField(service, "recoveryLeaseMs", 5_000L);

        org.junit.jupiter.api.Assertions.assertTrue(service.claimRecovery("order-3", 6L, 100L));
        org.junit.jupiter.api.Assertions.assertFalse(service.claimRecovery("order-3", 6L, 100L));
    }
}

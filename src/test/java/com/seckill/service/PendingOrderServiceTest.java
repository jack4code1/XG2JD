package com.seckill.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
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
        PendingOrderService service = new PendingOrderService(redis);
        TransactionSynchronizationManager.initSynchronization();

        service.acknowledgeAfterCommit("order-1", 6L);

        verify(redis, never()).delete("seckill:pending:order:order-1");
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(redis).delete("seckill:pending:order:order-1");
        verify(redis.opsForZSet()).remove("seckill:pending:orders", "order-1");
        verify(redis.opsForList()).remove("seckill:pending:6", 1, "order-1");
    }
}

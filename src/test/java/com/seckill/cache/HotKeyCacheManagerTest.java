package com.seckill.cache;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotKeyCacheManagerTest {

    @Test
    @SuppressWarnings("unchecked")
    void logicalExpiryReturnsStaleValueAndRefreshesUnderDistributedLock() throws Exception {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        HotKeyDetector detector = mock(HotKeyDetector.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redis.opsForValue()).thenReturn(values);
        when(detector.isHot("coupon:detail:1")).thenReturn(true);
        when(values.get("coupon:detail:1:v:1")).thenReturn(CacheValue.builder()
                .data(Map.of("couponName", "stale"))
                .logicExpireTime(System.currentTimeMillis() - 1)
                .physicalExpireTime(System.currentTimeMillis() + 60_000)
                .build());
        when(redisson.getLock("lock:cache:refresh:coupon:detail:1:v:1")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        HotKeyCacheManager manager = new HotKeyCacheManager(redis, detector, redisson);
        manager.init();
        CountDownLatch refreshed = new CountDownLatch(1);

        Map<String, Object> stale = manager.get("coupon:detail:1:v:1", "coupon:detail:1", () -> {
            refreshed.countDown();
            return CacheValue.<Map<String, Object>>builder()
                    .data(Map.of("couponName", "fresh"))
                    .logicExpireTime(Long.MAX_VALUE)
                    .physicalExpireTime(System.currentTimeMillis() + 60_000)
                    .build();
        });

        assertEquals("stale", stale.get("couponName"));
        assertTrue(refreshed.await(2, TimeUnit.SECONDS));
        Map<String, Object> fresh = manager.<Map<String, Object>>get("coupon:detail:1:v:1", "coupon:detail:1",
                () -> null);
        assertEquals("fresh", fresh.get("couponName"));
        verify(lock).unlock();
    }
}

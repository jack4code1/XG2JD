package com.seckill.service;

import com.seckill.cache.HotKeyCacheManager;
import com.seckill.cache.HotKeyDetector;
import com.seckill.cache.CacheValue;
import com.seckill.model.Coupon;
import com.seckill.perf.PerfCacheMetrics;
import com.seckill.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponCacheServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void readsTheSnapshotSelectedByTheActiveVersionPointer() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        HotKeyCacheManager localCache = mock(HotKeyCacheManager.class);
        HotKeyDetector detector = mock(HotKeyDetector.class);
        CouponRepository repository = mock(CouponRepository.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("coupon:detail:42:active")).thenReturn(4);

        Map<String, Object> expected = Map.of("id", 42L, "version", 4, "couponName", "夏日券");
        when(localCache.getWithLookup(eq("coupon:detail:42:v:4"), eq("coupon:detail:42"),
                org.mockito.ArgumentMatchers.<Supplier<CacheValue<Map<String, Object>>>>any()))
                .thenReturn(new HotKeyCacheManager.CacheLookup<>(expected, true, 0, 0));

        CouponCacheService service = new CouponCacheService(redis, localCache, detector, repository, redisson,
                mock(RabbitTemplate.class), noPerfMetrics());

        assertSame(expected, service.getCouponDetail(42L));
        verify(detector).record("coupon:detail:42");
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishesSnapshotBeforeSwitchingTheActivePointer() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        HotKeyCacheManager localCache = mock(HotKeyCacheManager.class);
        HotKeyDetector detector = mock(HotKeyDetector.class);
        CouponRepository repository = mock(CouponRepository.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redis.opsForValue()).thenReturn(values);

        Coupon coupon = Coupon.builder()
                .id(7L).merchantId(3L).couponName("新客券").couponDesc("仅限新客")
                .discountAmount(new BigDecimal("20")).totalStock(500).remainStock(500)
                .startTime(LocalDateTime.now().minusMinutes(1)).endTime(LocalDateTime.now().plusHours(1))
                .perUserMax(1).status(1).version(2).build();
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        CouponCacheService service = new CouponCacheService(redis, localCache, detector, repository, redisson,
                rabbitTemplate, noPerfMetrics());

        service.publish(coupon);

        verify(values).set(eq("coupon:detail:7:v:2"), org.mockito.ArgumentMatchers.isA(CacheValue.class),
                eq(Duration.ofMinutes(10)));
        verify(values).set("coupon:detail:7:active", 2);
        verify(localCache).evict("coupon:detail:7:v:2");
        assertEquals(2, coupon.getVersion());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PerfCacheMetrics> noPerfMetrics() {
        ObjectProvider<PerfCacheMetrics> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}

package com.seckill.service;

import com.seckill.cache.HotKeyCacheManager;
import com.seckill.cache.HotKeyDetector;
import com.seckill.model.Coupon;
import com.seckill.repository.CouponRepository;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

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
        when(localCache.get("coupon:detail:42:v:4", "coupon:detail:42", Map.class)).thenReturn(expected);

        CouponCacheService service = new CouponCacheService(redis, localCache, detector, repository, redisson);

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
        CouponCacheService service = new CouponCacheService(redis, localCache, detector, repository, redisson);

        service.publish(coupon);

        verify(values).set(eq("coupon:detail:7:v:2"), org.mockito.ArgumentMatchers.anyMap(),
                eq(Duration.ofMinutes(30)));
        verify(values).set("coupon:detail:7:active", 2);
        verify(localCache).putNormal(eq("coupon:detail:7:v:2"), org.mockito.ArgumentMatchers.anyMap());
        assertEquals(2, coupon.getVersion());
    }
}

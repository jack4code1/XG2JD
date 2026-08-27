package com.seckill.service;

import com.seckill.model.Coupon;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponSeckillStateServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void initializationWritesSeparateActivityAndStockKeys() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        Coupon coupon = Coupon.builder().id(8L).status(1).remainStock(99).perUserMax(1)
                .startTime(LocalDateTime.now()).endTime(LocalDateTime.now().plusHours(1)).build();

        new CouponSeckillStateService(redis).initialize(coupon);

        verify(hashes).putAll(eq("seckill:activity:8"), org.mockito.ArgumentMatchers.anyMap());
        verify(values).set("seckill:stock:8", "99");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stockReadFallsBackWhenRedisValueIsInvalid() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("seckill:stock:8")).thenReturn("not-a-number");

        assertEquals(7, new CouponSeckillStateService(redis).currentStock(8L, 7));
    }
}

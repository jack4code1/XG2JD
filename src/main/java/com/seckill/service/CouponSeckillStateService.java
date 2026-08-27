package com.seckill.service;

import com.seckill.constant.SeckillRedisKeys;
import com.seckill.model.Coupon;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Map;

/**
 * Owns the Redis state consumed by the seckill Lua script.
 * Stock is deliberately separate from activity metadata so DECR and state
 * reads have simple, observable Redis data types.
 */
@Service
@RequiredArgsConstructor
public class CouponSeckillStateService {
    private final StringRedisTemplate stringRedisTemplate;

    /** Initializes a newly created or explicitly migrated campaign. */
    public void initialize(Coupon coupon) {
        syncActivity(coupon);
        stringRedisTemplate.opsForValue().set(SeckillRedisKeys.stock(coupon.getId()),
                String.valueOf(coupon.getRemainStock()));
    }

    /** Updates lifecycle metadata without overwriting real-time Lua stock. */
    public void syncActivity(Coupon coupon) {
        stringRedisTemplate.opsForHash().putAll(SeckillRedisKeys.activity(coupon.getId()), Map.of(
                "status", String.valueOf(coupon.getStatus()),
                "start_time", String.valueOf(epoch(coupon)),
                "end_time", String.valueOf(coupon.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                "per_user_max", String.valueOf(coupon.getPerUserMax())));
    }

    /** Use only for an operator-approved stock adjustment or rollback. */
    public void replaceStock(Coupon coupon) {
        stringRedisTemplate.opsForValue().set(SeckillRedisKeys.stock(coupon.getId()),
                String.valueOf(coupon.getRemainStock()));
    }

    public int currentStock(Long couponId, int fallback) {
        String value = stringRedisTemplate.opsForValue().get(SeckillRedisKeys.stock(couponId));
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public Long restoreStock(Long couponId) {
        return stringRedisTemplate.opsForValue().increment(SeckillRedisKeys.stock(couponId));
    }

    /** Used only to compensate a failed campaign creation before it is exposed. */
    public void clear(Coupon coupon) {
        stringRedisTemplate.delete(java.util.List.of(
                SeckillRedisKeys.activity(coupon.getId()), SeckillRedisKeys.stock(coupon.getId()),
                SeckillRedisKeys.users(coupon.getId()), SeckillRedisKeys.userCount(coupon.getId()),
                SeckillRedisKeys.pending(coupon.getId())));
    }

    private long epoch(Coupon coupon) {
        return coupon.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

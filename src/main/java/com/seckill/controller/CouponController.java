package com.seckill.controller;

import com.seckill.model.Coupon;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券管理接口（运营后台用）
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 创建优惠券活动 + 预热到 Redis
     */
    @PostMapping("/create")
    public Coupon create(@RequestBody Coupon coupon) {
        requireMerchant();
        coupon.setMerchantId(merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId());
        coupon.setRemainStock(coupon.getTotalStock());
        coupon.setStatus(1);
        Coupon saved = couponRepository.save(coupon);

        String couponKey = "seckill:coupon:" + saved.getId();

        // 整数字段用 HSET（Redis 会存为整数，Lua 可 HINCRBY）
        redisTemplate.opsForHash().put(couponKey, "total", saved.getTotalStock());
        redisTemplate.opsForHash().put(couponKey, "remain", saved.getRemainStock());
        redisTemplate.opsForHash().put(couponKey, "version", 0);
        redisTemplate.opsForHash().put(couponKey, "per_user_max", saved.getPerUserMax());
        redisTemplate.opsForHash().put(couponKey, "status", 1);

        // 数值时间戳不会被 JSON 序列化器包裹引号，Lua 可直接比较。
        redisTemplate.opsForHash().put(couponKey, "start_time",
                saved.getStartTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        redisTemplate.opsForHash().put(couponKey, "end_time",
                saved.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        return saved;
    }

    /**
     * 查询进行中的活动
     */
    @GetMapping("/active")
    public List<Coupon> listActive() {
        return couponRepository.findByStatus(1);
    }

    private void requireMerchant() {
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("只有商家可以创建优惠券");
        }
    }
}

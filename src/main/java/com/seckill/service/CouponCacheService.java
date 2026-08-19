package com.seckill.service;

import com.seckill.cache.CacheValue;
import com.seckill.cache.HotKeyCacheManager;
import com.seckill.cache.HotKeyDetector;
import com.seckill.model.Coupon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 优惠券缓存服务 — L1(Caffeine) + L2(Redis) 二级缓存
 *
 * 面试可讲：
 * - 读路径：Caffeine L1 → Redis L2 → MySQL → 回填
 * - 写路径：先写 MySQL → Canal Binlog → MQ → 更新 Redis + 失效 Caffeine
 * - 热点自动升级：HotKeyDetector 标记 → Caffeine refreshAfterWrite(30s)
 * - 非热点：Caffeine expireAfterWrite(5min)，常规 TTL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final HotKeyCacheManager hotKeyCacheManager;
    private final HotKeyDetector hotKeyDetector;

    /**
     * 读取优惠券（带二级缓存）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCouponFromCache(Long couponId) {
        String cacheKey = "coupon:" + couponId;

        // L1: Caffeine（自动判断 NORMAL/HOT 模式）
        Map<String, Object> cached = hotKeyCacheManager.get(cacheKey, Map.class);
        if (cached != null) {
            return cached;
        }

        // L2: Redis
        String redisKey = "seckill:coupon:" + couponId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
        if (entries != null && !entries.isEmpty()) {
            // 回填 L1
            Map<String, Object> result = entries.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().toString(),
                            Map.Entry::getValue
                    ));
            hotKeyCacheManager.putNormal(cacheKey, result);
            return result;
        }

        log.debug("缓存未命中: couponId={}", couponId);
        return null;
    }

    /**
     * 记录优惠券访问（供热点检测）
     */
    public void recordAccess(Long couponId) {
        hotKeyDetector.record("coupon:" + couponId);
    }

    /**
     * 检查是否为热点优惠券
     */
    public boolean isHotCoupon(Long couponId) {
        return hotKeyDetector.isHot("coupon:" + couponId);
    }
}
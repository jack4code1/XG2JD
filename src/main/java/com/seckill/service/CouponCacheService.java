package com.seckill.service;

import com.seckill.cache.HotKeyCacheManager;
import com.seckill.cache.HotKeyDetector;
import com.seckill.model.Coupon;
import com.seckill.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Published coupon configuration cache.
 *
 * Stock and claimant state are deliberately excluded: they stay on the Redis
 * Lua path. This service caches only display/configuration fields that can be
 * safely served stale for a short period.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponCacheService {

    private static final String NULL_SENTINEL = "__NULL__";
    private static final Duration DETAIL_TTL = Duration.ofMinutes(30);
    private static final Duration NULL_TTL = Duration.ofMinutes(1);

    private final RedisTemplate<String, Object> redisTemplate;
    private final HotKeyCacheManager hotKeyCacheManager;
    private final HotKeyDetector hotKeyDetector;
    private final CouponRepository couponRepository;
    private final RedissonClient redissonClient;

    /**
     * Public read path: L1 Caffeine -> L2 immutable Redis snapshot -> MySQL.
     * The active-version pointer makes a published update atomic for readers.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCouponDetail(Long couponId) {
        String stableKey = stableDetailKey(couponId);
        hotKeyDetector.record(stableKey);

        Integer version = resolvePublishedVersion(couponId);
        if (version == null) return null;

        String versionKey = versionedDetailKey(couponId, version);
        Map<String, Object> l1 = hotKeyCacheManager.get(versionKey, stableKey, Map.class);
        if (l1 != null) return l1;

        Map<String, Object> l2 = readSnapshot(versionKey);
        if (l2 != null) {
            hotKeyCacheManager.putNormal(versionKey, l2);
            return l2;
        }

        // A Redis eviction is recoverable because the immutable DB version is
        // still authoritative. A distributed single-flight lock prevents a
        // hot key from causing many concurrent DB rebuilds.
        return rebuildSnapshot(couponId, versionKey);
    }

    /** Publish a complete immutable snapshot and atomically point readers to it. */
    public void publish(Coupon coupon) {
        if (coupon == null || coupon.getId() == null) {
            throw new IllegalArgumentException("优惠券必须先持久化后才能发布缓存快照");
        }
        int version = coupon.getVersion() == null ? 0 : coupon.getVersion();
        String activeKey = activeVersionKey(coupon.getId());
        Integer previousVersion = parseVersion(redisTemplate.opsForValue().get(activeKey));
        String versionKey = versionedDetailKey(coupon.getId(), version);

        Map<String, Object> snapshot = snapshotOf(coupon, version);
        redisTemplate.opsForValue().set(versionKey, snapshot, DETAIL_TTL);
        // The pointer is written only after the snapshot exists, so a reader
        // sees either the old complete version or the new complete version.
        redisTemplate.opsForValue().set(activeKey, version);
        hotKeyCacheManager.putNormal(versionKey, snapshot);

        if (previousVersion != null && previousVersion != version) {
            hotKeyCacheManager.evict(versionedDetailKey(coupon.getId(), previousVersion));
        }
        log.debug("优惠券配置快照已发布: couponId={}, version={}, lifecycle={}",
                coupon.getId(), version, snapshot.get("lifecycle"));
    }

    /** Remove a deleted coupon from both cache tiers and make misses short-lived. */
    public void evict(Long couponId) {
        Integer version = parseVersion(redisTemplate.opsForValue().get(activeVersionKey(couponId)));
        if (version != null) {
            String versionKey = versionedDetailKey(couponId, version);
            hotKeyCacheManager.evict(versionKey);
            redisTemplate.delete(versionKey);
        }
        redisTemplate.delete(activeVersionKey(couponId));
    }

    /** Backward-compatible entry point used by existing callers. */
    public Map<String, Object> getCouponFromCache(Long couponId) {
        return getCouponDetail(couponId);
    }

    public void recordAccess(Long couponId) {
        hotKeyDetector.record(stableDetailKey(couponId));
    }

    public boolean isHotCoupon(Long couponId) {
        return hotKeyDetector.isHot(stableDetailKey(couponId));
    }

    public Map<String, Object> cacheStatus(Long couponId) {
        Integer version = parseVersion(redisTemplate.opsForValue().get(activeVersionKey(couponId)));
        return Map.of(
                "couponId", couponId,
                "activeVersion", version == null ? -1 : version,
                "hot", isHotCoupon(couponId),
                "l1HitRate", hotKeyCacheManager.getNormalCacheStats().hitRate(),
                "l1Evictions", hotKeyCacheManager.getNormalCacheStats().evictionCount());
    }

    private Integer resolvePublishedVersion(Long couponId) {
        Object cached = redisTemplate.opsForValue().get(activeVersionKey(couponId));
        if (NULL_SENTINEL.equals(String.valueOf(cached))) return null;
        Integer version = parseVersion(cached);
        if (version != null) return version;

        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null) {
            cacheMissing(couponId);
            return null;
        }
        publish(coupon);
        return coupon.getVersion() == null ? 0 : coupon.getVersion();
    }

    private void cacheMissing(Long couponId) {
        redisTemplate.opsForValue().set(activeVersionKey(couponId), NULL_SENTINEL, NULL_TTL);
    }

    private Map<String, Object> rebuildSnapshot(Long couponId, String requestedVersionKey) {
        RLock lock = redissonClient.getLock("lock:coupon:detail:" + couponId);
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!locked) {
                // Another application instance is rebuilding. Recheck once
                // instead of allowing every caller to fall through to MySQL.
                return readSnapshot(requestedVersionKey);
            }
            Map<String, Object> rebuiltByPeer = readSnapshot(requestedVersionKey);
            if (rebuiltByPeer != null) return rebuiltByPeer;

            Coupon coupon = couponRepository.findById(couponId).orElse(null);
            if (coupon == null) {
                cacheMissing(couponId);
                return null;
            }
            publish(coupon);
            int currentVersion = coupon.getVersion() == null ? 0 : coupon.getVersion();
            return readSnapshot(versionedDetailKey(couponId, currentVersion));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSnapshot(String key) {
        Object cached = redisTemplate.opsForValue().get(key);
        if (!(cached instanceof Map<?, ?> raw)) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        raw.forEach((field, value) -> snapshot.put(String.valueOf(field), value));
        return snapshot;
    }

    private Map<String, Object> snapshotOf(Coupon coupon, int version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", coupon.getId());
        snapshot.put("merchantId", coupon.getMerchantId());
        snapshot.put("couponName", coupon.getCouponName());
        snapshot.put("couponDesc", coupon.getCouponDesc());
        snapshot.put("discountAmount", coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount());
        // Redis uses a generic Object serializer for this snapshot. Store the
        // time fields as ISO-8601 strings so their runtime type is explicit
        // and the serializer never has to resolve LocalDateTime through
        // Object (which causes type-id serialization to fail).
        snapshot.put("startTime", coupon.getStartTime() == null ? null : coupon.getStartTime().toString());
        snapshot.put("endTime", coupon.getEndTime() == null ? null : coupon.getEndTime().toString());
        snapshot.put("perUserMax", coupon.getPerUserMax());
        snapshot.put("status", coupon.getStatus());
        snapshot.put("version", version);
        snapshot.put("lifecycle", lifecycleOf(coupon));
        return snapshot;
    }

    private String lifecycleOf(Coupon coupon) {
        if (coupon.getStatus() != null && coupon.getStatus() == 3) return "PAUSED";
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStartTime() != null && now.isBefore(coupon.getStartTime())) return "SCHEDULED";
        if (coupon.getEndTime() != null && now.isAfter(coupon.getEndTime())) return "ENDED";
        return "ACTIVE";
    }

    private Integer parseVersion(Object value) {
        if (value == null || NULL_SENTINEL.equals(String.valueOf(value))) return null;
        try {
            return Integer.parseInt(String.valueOf(value).replace("\"", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String stableDetailKey(Long couponId) { return "coupon:detail:" + couponId; }
    private String activeVersionKey(Long couponId) { return stableDetailKey(couponId) + ":active"; }
    private String versionedDetailKey(Long couponId, int version) {
        return stableDetailKey(couponId) + ":v:" + version;
    }
}

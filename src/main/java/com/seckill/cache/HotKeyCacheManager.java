package com.seckill.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 热点缓存管理器 — 动态升降级
 *
 * 面试可讲：
 * - NORMAL 模式：expireAfterWrite(5min)，读缓存未命中 → 查 Redis → 回填
 * - HOT 模式：refreshAfterWrite(30s) + 逻辑过期，读缓存发现逻辑过期 → 返回旧值 + 异步刷新
 * - 自动降级：HotKeyDetector 连续3轮低于阈值 → 恢复 NORMAL 模式
 * - 异步重建用独立线程池，不阻塞 Caffeine 的内部线程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotKeyCacheManager {

    /** Result of a single Caffeine lookup and any L2 snapshot lookup it performed. */
    public record CacheLookup<T>(T value, boolean caffeineHit, int redisSnapshotReads, int redisSnapshotHits) {}

    private final RedisTemplate<String, Object> redisTemplate;
    private final HotKeyDetector hotKeyDetector;
    private final RedissonClient redissonClient;

    /** NORMAL 模式缓存：适用冷数据 */
    private Cache<String, Object> normalCache;

    /** HOT 模式缓存：适用热点数据，key → CacheValue<CouponVO> */
    private final ConcurrentHashMap<String, Cache<String, CacheValue<?>>> hotCaches = new ConcurrentHashMap<>();

    /** 异步重建线程池 */
    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cache-rebuild");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        normalCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats()
                .build();
        log.info("缓存管理器初始化: normalCache(maxSize=10000, ttl=5min)");
    }

    /**
     * 从缓存获取数据（自动判断 NORMAL/HOT 模式）
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        return get(key, key, type);
    }

    /**
     * Reads a cache entry while allowing the access metric key to remain stable
     * across immutable cache versions. For example, a coupon detail can be
     * cached under coupon:detail:12:v:4 while its hotness is tracked as
     * coupon:detail:12.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, String hotKey, Class<T> type) {
        return get(key, hotKey, type, () -> null);
    }

    /**
     * Reads a cache entry and, for a logically expired hot value, refreshes it
     * asynchronously with a distributed single-flight lock.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, String hotKey, Class<T> type, Supplier<CacheValue<T>> refreshLoader) {
        return getWithLookup(key, hotKey, type, refreshLoader).value();
    }

    public <T> T get(String key, String hotKey, Supplier<CacheValue<T>> refreshLoader) {
        return getWithLookup(key, hotKey, null, refreshLoader).value();
    }

    public <T> CacheLookup<T> getWithLookup(String key, String hotKey, Supplier<CacheValue<T>> refreshLoader) {
        return getWithLookup(key, hotKey, null, refreshLoader);
    }

    /**
     * Same cache behavior as {@link #get(String, String, Supplier)}, plus
     * enough path information for the perf profile to count real L1/L2 work.
     */
    @SuppressWarnings("unchecked")
    public <T> CacheLookup<T> getWithLookup(String key, String hotKey, Class<T> type,
                                             Supplier<CacheValue<T>> refreshLoader) {
        if (hotKeyDetector.isHot(hotKey)) {
            return getFromHotCacheWithLookup(key, type, refreshLoader);
        }
        hotCaches.remove(key);
        return getFromNormalCacheWithLookup(key, type);
    }

    /**
     * NORMAL 模式读取：Caffeine → Redis → null
     */
    @SuppressWarnings("unchecked")
    private <T> T getFromNormalCache(String key, Class<T> type) {
        return getFromNormalCacheWithLookup(key, type).value();
    }

    @SuppressWarnings("unchecked")
    private <T> CacheLookup<T> getFromNormalCacheWithLookup(String key, Class<T> type) {
        Object value = normalCache.getIfPresent(key);
        if (value != null) {
            return new CacheLookup<>((T) unwrap(value), true, 0, 0);
        }
        // 回源 Redis
        Object redisValue = redisTemplate.opsForValue().get(key);
        if (redisValue != null) {
            normalCache.put(key, redisValue);
            return new CacheLookup<>((T) unwrap(redisValue), false, 1, 1);
        }
        return new CacheLookup<>(null, false, 1, 0);
    }

    /**
     * HOT 模式读取：Caffeine(CacheValue) → 逻辑过期判定 → 旧值兜底 + 异步刷新
     */
    @SuppressWarnings("unchecked")
    private <T> T getFromHotCache(String key, Class<T> type, Supplier<CacheValue<T>> refreshLoader) {
        return getFromHotCacheWithLookup(key, type, refreshLoader).value();
    }

    @SuppressWarnings("unchecked")
    private <T> CacheLookup<T> getFromHotCacheWithLookup(String key, Class<T> type,
                                                          Supplier<CacheValue<T>> refreshLoader) {
        // 获取或创建 hotCache（使用 computeIfAbsent 保证只赋值一次）
        Cache<String, CacheValue<?>> hotCache = hotCaches.get(key);
        if (hotCache == null) {
            Cache<String, CacheValue<?>> candidate = createHotCache();
            Cache<String, CacheValue<?>> existing = hotCaches.putIfAbsent(key, candidate);
            if (existing == null) {
                Object redisValue = redisTemplate.opsForValue().get(key);
                if (redisValue != null) candidate.put(key, toCacheValue(redisValue));
                CacheValue<?> loaded = candidate.getIfPresent(key);
                if (loaded != null && loaded.isLogicallyExpired()) {
                    rebuildExecutor.submit(() -> refreshHotCache(key, candidate, refreshLoader));
                }
                return new CacheLookup<>(loaded == null ? null : (T) loaded.getData(), false, 1,
                        redisValue == null ? 0 : 1);
            }
            hotCache = existing;
        }

        CacheValue<?> cacheValue = hotCache.getIfPresent(key);
        if (cacheValue == null) {
            return new CacheLookup<>(null, false, 0, 0);
        }

        // 逻辑过期 → 返回旧值 + 异步刷新
        if (cacheValue.isLogicallyExpired()) {
            Cache<String, CacheValue<?>> cacheForRefresh = hotCache;
            rebuildExecutor.submit(() -> refreshHotCache(key, cacheForRefresh, refreshLoader));
        }

        return new CacheLookup<>((T) cacheValue.getData(), true, 0, 0);
    }

    /**
     * NORMAL 模式写入
     */
    public void putNormal(String key, Object value) {
        normalCache.put(key, value);
    }

    /** Evict a single immutable version from both local cache tiers. */
    public void evict(String key) {
        normalCache.invalidate(key);
        Cache<String, CacheValue<?>> hotCache = hotCaches.remove(key);
        if (hotCache != null) {
            hotCache.invalidate(key);
        }
    }

    /**
     * 降级处理：HotKeyDetector 判定不再热点，移除 hotCache
     */
    public void downgrade(String key) {
        hotCaches.remove(key);
        log.info("缓存降级: key={}, hotCache已移除", key);
    }

    /**
     * 获取 Caffeine 统计（暴露给 Prometheus）
     */
    public CacheStats getNormalCacheStats() {
        return normalCache.stats();
    }

    private Cache<String, CacheValue<?>> createHotCache() {
        return Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    private <T> void refreshHotCache(String key, Cache<String, CacheValue<?>> hotCache,
                                     Supplier<CacheValue<T>> refreshLoader) {
        RLock lock = redissonClient.getLock("lock:cache:refresh:" + key);
        boolean locked = false;
        try {
            locked = lock.tryLock();
            if (!locked) {
                return;
            }
            CacheValue<T> fresh = refreshLoader.get();
            if (fresh != null) {
                hotCache.put(key, fresh);
                log.debug("热点缓存刷新: key={}", key);
            }
        } catch (Exception e) {
            log.error("热点缓存刷新失败: key={}", key, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Object unwrap(Object value) {
        return value instanceof CacheValue<?> cacheValue ? cacheValue.getData() : value;
    }

    @SuppressWarnings("unchecked")
    private CacheValue<?> toCacheValue(Object redisValue) {
        if (redisValue instanceof CacheValue<?> cacheValue) {
            return cacheValue;
        }
        // Normal lifecycle entries have Redis TTL and must not trigger a
        // logical-expiry refresh merely because the key becomes hot later.
        return CacheValue.builder()
                .data(redisValue)
                .logicExpireTime(Long.MAX_VALUE)
                .physicalExpireTime(System.currentTimeMillis() + Duration.ofMinutes(10).toMillis())
                .build();
    }
}

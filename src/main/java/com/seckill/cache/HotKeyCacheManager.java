package com.seckill.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private final RedisTemplate<String, Object> redisTemplate;
    private final HotKeyDetector hotKeyDetector;

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
        if (hotKeyDetector.isHot(key)) {
            return getFromHotCache(key, type);
        }
        return getFromNormalCache(key, type);
    }

    /**
     * NORMAL 模式读取：Caffeine → Redis → null
     */
    @SuppressWarnings("unchecked")
    private <T> T getFromNormalCache(String key, Class<T> type) {
        Object value = normalCache.getIfPresent(key);
        if (value != null) {
            return (T) value;
        }
        // 回源 Redis
        Object redisValue = redisTemplate.opsForValue().get(key);
        if (redisValue != null) {
            normalCache.put(key, redisValue);
            return (T) redisValue;
        }
        return null;
    }

    /**
     * HOT 模式读取：Caffeine(CacheValue) → 逻辑过期判定 → 旧值兜底 + 异步刷新
     */
    @SuppressWarnings("unchecked")
    private <T> T getFromHotCache(String key, Class<T> type) {
        // 获取或创建 hotCache（使用 computeIfAbsent 保证只赋值一次）
        final Cache<String, CacheValue<?>> hotCache = hotCaches.computeIfAbsent(key, k -> {
            Cache<String, CacheValue<?>> newCache = createHotCache();
            // 预热：从 Redis 加载
            Object redisValue = redisTemplate.opsForValue().get(key);
            if (redisValue != null) {
                CacheValue<Object> cv = CacheValue.builder()
                        .data(redisValue)
                        .logicExpireTime(System.currentTimeMillis() + 30_000)
                        .physicalExpireTime(System.currentTimeMillis() + 600_000)
                        .build();
                newCache.put(key, cv);
            }
            return newCache;
        });

        CacheValue<?> cacheValue = hotCache.getIfPresent(key);
        if (cacheValue == null) {
            return null;
        }

        // 逻辑过期 → 返回旧值 + 异步刷新
        if (cacheValue.isLogicallyExpired()) {
            rebuildExecutor.submit(() -> refreshHotCache(key, hotCache));
            // 物理过期兜底 → 必须重建
            if (cacheValue.isPhysicallyExpired()) {
                Object fresh = redisTemplate.opsForValue().get(key);
                return fresh != null ? (T) fresh : (T) cacheValue.getData();
            }
        }

        return (T) cacheValue.getData();
    }

    /**
     * NORMAL 模式写入
     */
    public void putNormal(String key, Object value) {
        normalCache.put(key, value);
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
                .refreshAfterWrite(30, TimeUnit.SECONDS)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    private void refreshHotCache(String key, Cache<String, CacheValue<?>> hotCache) {
        try {
            Object fresh = redisTemplate.opsForValue().get(key);
            if (fresh != null) {
                CacheValue<Object> cv = CacheValue.builder()
                        .data(fresh)
                        .logicExpireTime(System.currentTimeMillis() + 30_000)
                        .physicalExpireTime(System.currentTimeMillis() + 600_000)
                        .build();
                hotCache.put(key, cv);
                log.debug("热点缓存刷新: key={}", key);
            }
        } catch (Exception e) {
            log.error("热点缓存刷新失败: key={}", key, e);
        }
    }
}
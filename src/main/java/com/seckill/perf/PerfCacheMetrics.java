package com.seckill.perf;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run cache-path counters used only by the local perf profile.
 * They deliberately track the coupon-detail path rather than global Redis
 * statistics, which also include authentication and seckill traffic.
 */
@Profile("perf")
@Component
public class PerfCacheMetrics {
    private final MeterRegistry meterRegistry;
    private final AtomicLong caffeineHit = new AtomicLong();
    private final AtomicLong caffeineMiss = new AtomicLong();
    private final AtomicLong redisSnapshotHit = new AtomicLong();
    private final AtomicLong redisSnapshotMiss = new AtomicLong();
    private final AtomicLong redisVersionPointerRead = new AtomicLong();
    private final AtomicLong dbLoad = new AtomicLong();
    private volatile Instant resetAt = Instant.now();

    public PerfCacheMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bindGauges() {
        Gauge.builder("perf.cache.caffeine.hit", caffeineHit, AtomicLong::get).register(meterRegistry);
        Gauge.builder("perf.cache.caffeine.miss", caffeineMiss, AtomicLong::get).register(meterRegistry);
        Gauge.builder("perf.cache.redis.snapshot.hit", redisSnapshotHit, AtomicLong::get).register(meterRegistry);
        Gauge.builder("perf.cache.redis.snapshot.miss", redisSnapshotMiss, AtomicLong::get).register(meterRegistry);
        Gauge.builder("perf.cache.redis.version.pointer.read", redisVersionPointerRead, AtomicLong::get)
                .register(meterRegistry);
        Gauge.builder("perf.cache.db.load", dbLoad, AtomicLong::get).register(meterRegistry);
    }

    public void recordCaffeineHit() { caffeineHit.incrementAndGet(); }
    public void recordCaffeineMiss() { caffeineMiss.incrementAndGet(); }
    public void recordRedisSnapshot(boolean hit) {
        (hit ? redisSnapshotHit : redisSnapshotMiss).incrementAndGet();
    }
    public void recordRedisVersionPointerRead() { redisVersionPointerRead.incrementAndGet(); }
    public void recordDbLoad() { dbLoad.incrementAndGet(); }

    public synchronized void reset() {
        caffeineHit.set(0);
        caffeineMiss.set(0);
        redisSnapshotHit.set(0);
        redisSnapshotMiss.set(0);
        redisVersionPointerRead.set(0);
        dbLoad.set(0);
        resetAt = Instant.now();
    }

    public Map<String, Object> snapshot() {
        long hits = caffeineHit.get();
        long misses = caffeineMiss.get();
        long requests = hits + misses;
        long snapshotHits = redisSnapshotHit.get();
        long snapshotMisses = redisSnapshotMiss.get();
        return Map.ofEntries(
                Map.entry("resetAt", resetAt.toString()),
                Map.entry("caffeine_hit", hits),
                Map.entry("caffeine_miss", misses),
                Map.entry("caffeine_hit_rate", rate(hits, requests)),
                Map.entry("redis_snapshot_hit", snapshotHits),
                Map.entry("redis_snapshot_miss", snapshotMisses),
                Map.entry("redis_snapshot_hit_rate", rate(snapshotHits, snapshotHits + snapshotMisses)),
                Map.entry("redis_version_pointer_read", redisVersionPointerRead.get()),
                Map.entry("db_load", dbLoad.get()),
                Map.entry("detail_requests_observed", requests),
                Map.entry("db_back_source_rate", rate(dbLoad.get(), requests))
        );
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0D : (double) numerator / denominator;
    }
}

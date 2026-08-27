package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.cache.CacheValue;
import com.seckill.exception.ForbiddenException;
import com.seckill.model.Coupon;
import com.seckill.perf.PerfCacheMetrics;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.service.CouponCacheService;
import com.seckill.service.CouponSeckillStateService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Local-only controls for repeatable cache experiments. Never loaded outside perf. */
@Profile("perf")
@RestController
@RequestMapping("/api/perf/cache")
@RequiredArgsConstructor
public class PerfCacheController {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final String FIXTURE_PREFIX = "perf_cache_";

    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final CouponCacheService couponCacheService;
    private final CouponSeckillStateService couponSeckillStateService;
    private final PerfCacheMetrics perfCacheMetrics;
    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/fixtures")
    @Transactional
    public Result<Map<String, Object>> createFixtures(@RequestBody FixtureRequest request) {
        requireMerchant();
        String runId = requireRunId(request.runId());
        int count = request.count() == null ? 100 : request.count();
        if (count < 10 || count > 500) throw new IllegalArgumentException("测试优惠券数量必须在 10-500");

        LocalDateTime now = LocalDateTime.now();
        List<Long> couponIds = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            Coupon coupon = Coupon.builder()
                    .couponName(FIXTURE_PREFIX + runId + "_" + index)
                    .couponDesc("perf cache fixture; safe to remove by run id")
                    .merchantId(merchantId())
                    .discountAmount(BigDecimal.ONE)
                    .totalStock(10_000)
                    .remainStock(10_000)
                    .startTime(now.minusMinutes(1))
                    .endTime(now.plusHours(2))
                    .perUserMax(1)
                    .status(1)
                    .version(0)
                    .build();
            Coupon saved = couponRepository.save(coupon);
            couponSeckillStateService.initialize(saved);
            couponCacheService.publish(saved);
            couponIds.add(saved.getId());
        }
        return Result.ok(Map.of("runId", runId, "couponIds", couponIds, "hotCouponIds", couponIds.subList(0, 10)));
    }

    @DeleteMapping("/fixtures/{runId}")
    @Transactional
    public Result<Map<String, Object>> deleteFixtures(@PathVariable String runId) {
        requireMerchant();
        String checkedRunId = requireRunId(runId);
        String prefix = FIXTURE_PREFIX + checkedRunId + "_";
        List<Coupon> fixtures = couponRepository.findAll().stream()
                .filter(coupon -> coupon.getCouponName() != null && coupon.getCouponName().startsWith(prefix))
                .toList();
        fixtures.forEach(coupon -> {
            couponCacheService.clearLocalDetailCache(coupon.getId());
            couponCacheService.clearRedisDetailCache(coupon.getId());
            couponSeckillStateService.clear(coupon);
        });
        couponRepository.deleteAll(fixtures);
        return Result.ok(Map.of("runId", checkedRunId, "deleted", fixtures.size()));
    }

    @PostMapping("/reset")
    public Result<Map<String, Object>> reset(@RequestBody CacheControlRequest request) {
        requireMerchant();
        List<Coupon> coupons = requirePerfCoupons(request.couponIds());
        boolean clearL1 = request.clearL1() == null || request.clearL1();
        boolean clearRedis = request.clearRedis() == null || request.clearRedis();
        for (Coupon coupon : coupons) {
            if (clearL1) couponCacheService.clearLocalDetailCache(coupon.getId());
            if (clearRedis) couponCacheService.clearRedisDetailCache(coupon.getId());
        }
        if (request.resetMetrics() == null || request.resetMetrics()) perfCacheMetrics.reset();
        return Result.ok(Map.of("couponCount", coupons.size(), "clearL1", clearL1,
                "clearRedis", clearRedis, "metrics", perfCacheMetrics.snapshot()));
    }

    @PostMapping("/prewarm")
    public Result<Map<String, Object>> prewarm(@RequestBody PrewarmRequest request) {
        requireMerchant();
        List<Coupon> coupons = requirePerfCoupons(request.couponIds());
        PrewarmMode mode = request.mode() == null ? PrewarmMode.CAFFEINE_SNAPSHOT : request.mode();
        for (Coupon coupon : coupons) {
            // Each formal sample starts from the same published, unexpired snapshot.
            // This renews only cache TTL; the persisted coupon and its version do not change.
            couponCacheService.publish(coupon);
            couponCacheService.getCouponDetail(coupon.getId());
            if (mode == PrewarmMode.REDIS_SNAPSHOT) couponCacheService.clearLocalDetailCache(coupon.getId());
        }
        return Result.ok(Map.of("couponCount", coupons.size(), "mode", mode.name()));
    }

    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        requireMerchant();
        return Result.ok(perfCacheMetrics.snapshot());
    }

    /**
     * Three equivalent detail-read paths for local cache experiments. The
     * endpoint is loaded only by the perf profile; production traffic keeps
     * using CouponController.detail and CouponCacheService.getCouponDetail.
     */
    @GetMapping("/read/{mode}/{couponId}")
    public Result<Map<String, Object>> read(@PathVariable CacheReadMode mode, @PathVariable Long couponId) {
        Map<String, Object> detail = switch (mode) {
            case MYSQL -> readFromMysql(couponId);
            case REDIS -> readFromRedisSnapshot(couponId);
            case CAFFEINE -> couponCacheService.getCouponDetail(couponId);
        };
        if (detail == null) throw new IllegalArgumentException("优惠券不存在或缓存快照缺失");
        return Result.ok(detail);
    }

    public record FixtureRequest(String runId, Integer count) {}
    public record CacheControlRequest(List<Long> couponIds, Boolean clearL1, Boolean clearRedis, Boolean resetMetrics) {}
    public record PrewarmRequest(List<Long> couponIds, PrewarmMode mode) {}
    public enum PrewarmMode { REDIS_SNAPSHOT, CAFFEINE_SNAPSHOT }
    public enum CacheReadMode { MYSQL, REDIS, CAFFEINE }

    private Map<String, Object> readFromMysql(Long couponId) {
        // COSEC: JpaRepository binds the path value as a typed identifier; no SQL is concatenated.
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        perfCacheMetrics.recordDbLoad();
        return coupon == null ? null : snapshotOf(coupon);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFromRedisSnapshot(Long couponId) {
        perfCacheMetrics.recordRedisVersionPointerRead();
        Integer version = parseVersion(redisTemplate.opsForValue().get(activeVersionKey(couponId)));
        if (version == null) return null;

        Object cached = redisTemplate.opsForValue().get(versionedDetailKey(couponId, version));
        if (cached instanceof CacheValue<?> cacheValue) cached = cacheValue.getData();
        if (!(cached instanceof Map<?, ?> raw)) {
            perfCacheMetrics.recordRedisSnapshot(false);
            return null;
        }
        if (raw.containsKey("data") && raw.get("data") instanceof Map<?, ?> wrapped) raw = wrapped;
        Map<String, Object> detail = new LinkedHashMap<>();
        raw.forEach((key, value) -> detail.put(String.valueOf(key), value));
        perfCacheMetrics.recordRedisSnapshot(true);
        return detail;
    }

    private Map<String, Object> snapshotOf(Coupon coupon) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", coupon.getId());
        detail.put("merchantId", coupon.getMerchantId());
        detail.put("couponName", coupon.getCouponName());
        detail.put("couponDesc", coupon.getCouponDesc());
        detail.put("discountAmount", coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount());
        detail.put("startTime", coupon.getStartTime() == null ? null : coupon.getStartTime().toString());
        detail.put("endTime", coupon.getEndTime() == null ? null : coupon.getEndTime().toString());
        detail.put("perUserMax", coupon.getPerUserMax());
        detail.put("status", coupon.getStatus());
        detail.put("version", coupon.getVersion() == null ? 0 : coupon.getVersion());
        detail.put("lifecycle", lifecycleOf(coupon));
        return detail;
    }

    private String lifecycleOf(Coupon coupon) {
        if (coupon.getStatus() != null && coupon.getStatus() == 3) return "PAUSED";
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStartTime() != null && now.isBefore(coupon.getStartTime())) return "SCHEDULED";
        if (coupon.getEndTime() != null && now.isAfter(coupon.getEndTime())) return "ENDED";
        return "ACTIVE";
    }

    private Integer parseVersion(Object value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(String.valueOf(value).replace("\"", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String activeVersionKey(Long couponId) { return "coupon:detail:" + couponId + ":active"; }
    private String versionedDetailKey(Long couponId, int version) {
        return "coupon:detail:" + couponId + ":v:" + version;
    }

    private List<Coupon> requirePerfCoupons(List<Long> couponIds) {
        if (couponIds == null || couponIds.isEmpty() || couponIds.size() > 500) {
            throw new IllegalArgumentException("必须提供 1-500 个测试优惠券");
        }
        List<Coupon> coupons = couponRepository.findAllById(couponIds);
        if (coupons.size() != couponIds.stream().distinct().count()) {
            throw new IllegalArgumentException("存在不存在的测试优惠券");
        }
        // COSEC: perf controls must never derive or delete keys for normal business coupons.
        if (coupons.stream().anyMatch(coupon -> coupon.getCouponName() == null
                || !coupon.getCouponName().startsWith("perf_"))) {
            throw new ForbiddenException("仅允许操作 couponName 以 perf_ 开头的测试优惠券");
        }
        return coupons;
    }

    private String requireRunId(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("runId 仅允许字母、数字、下划线和连字符，长度 1-40");
        }
        return runId;
    }

    private Long merchantId() {
        return merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("当前商家没有店铺"))
                .getId();
    }

    private void requireMerchant() {
        // COSEC: the perf-only mutation endpoints still require merchant authorization.
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("仅商家账号可操作性能测试夹具");
        }
    }
}

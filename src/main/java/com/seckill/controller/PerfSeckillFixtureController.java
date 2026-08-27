package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.constant.SeckillRedisKeys;
import com.seckill.exception.ForbiddenException;
import com.seckill.model.Coupon;
import com.seckill.model.EventLog;
import com.seckill.model.Merchant;
import com.seckill.model.Order;
import com.seckill.model.User;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.UserRepository;
import com.seckill.service.CouponCacheService;
import com.seckill.service.CouponSeckillStateService;
import com.seckill.service.TokenService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Local-only fixtures for reproducible seckill smoke and load tests. */
@Profile("perf")
@RestController
@RequestMapping("/api/perf/seckill")
@RequiredArgsConstructor
public class PerfSeckillFixtureController {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final String USER_PREFIX = "perf_seckill_user_";
    private static final String MERCHANT_PREFIX = "perf_seckill_merchant_";
    private static final String COUPON_PREFIX = "perf_seckill_coupon_";
    private static final String TOKEN_INDEX_PREFIX = "perf:seckill:tokens:";
    private static final String DEVICE_PREFIX = "perf-seckill-device-";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration TRACKER_TTL = Duration.ofHours(2);

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final CouponRepository couponRepository;
    private final OrderRepository orderRepository;
    private final EventLogRepository eventLogRepository;
    private final CouponSeckillStateService couponSeckillStateService;
    private final CouponCacheService couponCacheService;
    private final TokenService tokenService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/fixtures")
    @Transactional
    public Result<FixtureResponse> createFixtures(@RequestBody FixtureRequest request) {
        requireMerchant();
        String runId = requireRunId(request.runId());
        int userCount = bounded(request.userCount(), 1, 5_000, 20, "userCount");
        int shopCount = bounded(request.shopCount(), 1, 100, 1, "shopCount");
        int couponCount = bounded(request.couponCount(), 1, 100, 1, "couponCount");
        int stockPerCoupon = bounded(request.stockPerCoupon(), 1, 100_000, userCount, "stockPerCoupon");
        ensureAbsent(runId);

        String fixturePasswordHash = passwordEncoder.encode(randomToken());
        List<Long> merchantIds = createMerchants(runId, shopCount, fixturePasswordHash);
        List<Long> couponIds = createCoupons(runId, couponCount, stockPerCoupon, merchantIds);
        List<FixtureUser> users = createUsers(runId, userCount, couponIds, fixturePasswordHash);
        return Result.ok(new FixtureResponse(runId, merchantIds, couponIds, users));
    }

    @DeleteMapping("/fixtures")
    @Transactional
    public Result<Map<String, Object>> deleteFixtures(@RequestBody CleanupRequest request) {
        requireMerchant();
        String runId = requireRunId(request.runId());
        List<Coupon> coupons = couponRepository.findByCouponNameStartingWith(COUPON_PREFIX + runId + "_");
        ensureNoPendingOrders(coupons);

        List<Long> couponIds = coupons.stream().map(Coupon::getId).toList();
        List<Order> orders = couponIds.isEmpty() ? List.of() : orderRepository.findByCouponIdIn(couponIds);
        List<String> orderNos = orders.stream().map(Order::getOrderNo).toList();
        List<EventLog> events = orderNos.isEmpty() ? List.of() : eventLogRepository.findByAggregateIdIn(orderNos);
        List<User> users = userRepository.findByUsernameStartingWith(USER_PREFIX + runId + "_");
        List<User> merchantUsers = userRepository.findByUsernameStartingWith(MERCHANT_PREFIX + runId + "_");
        List<Long> merchantUserIds = merchantUsers.stream().map(User::getId).toList();
        List<Merchant> merchants = merchantUserIds.isEmpty() ? List.of() : merchantRepository.findByUserIdIn(merchantUserIds);

        coupons.forEach(coupon -> {
            couponCacheService.evict(coupon.getId());
            couponSeckillStateService.clear(coupon);
        });
        eventLogRepository.deleteAll(events);
        orderRepository.deleteAll(orders);
        couponRepository.deleteAll(coupons);
        merchantRepository.deleteAll(merchants);
        userRepository.deleteAll(users);
        userRepository.deleteAll(merchantUsers);
        clearAccessTokens(runId);

        return Result.ok(Map.of("runId", runId, "deletedCoupons", coupons.size(),
                "deletedOrders", orders.size(), "deletedUsers", users.size(),
                "deletedShops", merchants.size()));
    }

    private List<Long> createMerchants(String runId, int shopCount, String passwordHash) {
        List<Long> merchantIds = new ArrayList<>(shopCount);
        for (int index = 1; index <= shopCount; index++) {
            User user = userRepository.save(User.builder()
                    .username(MERCHANT_PREFIX + runId + "_" + index)
                    .password(passwordHash).role("MERCHANT").build());
            Merchant merchant = merchantRepository.save(Merchant.builder()
                    .userId(user.getId()).shopName("Perf Shop " + runId + " " + index)
                    .shopDesc("Isolated performance-test fixture").category("其他").build());
            merchantIds.add(merchant.getId());
        }
        return merchantIds;
    }

    private List<Long> createCoupons(String runId, int couponCount, int stockPerCoupon, List<Long> merchantIds) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> couponIds = new ArrayList<>(couponCount);
        for (int index = 1; index <= couponCount; index++) {
            Coupon coupon = couponRepository.save(Coupon.builder()
                    .couponName(COUPON_PREFIX + runId + "_" + index)
                    .couponDesc("Isolated performance-test fixture")
                    .merchantId(merchantIds.get((index - 1) % merchantIds.size()))
                    .discountAmount(BigDecimal.ONE).totalStock(stockPerCoupon).remainStock(stockPerCoupon)
                    .startTime(now.minusMinutes(1)).endTime(now.plusHours(2))
                    .perUserMax(1).status(1).version(0).build());
            couponSeckillStateService.initialize(coupon);
            couponCacheService.publish(coupon);
            couponIds.add(coupon.getId());
        }
        return couponIds;
    }

    private List<FixtureUser> createUsers(String runId, int userCount, List<Long> couponIds, String passwordHash) {
        List<FixtureUser> users = new ArrayList<>(userCount);
        for (int index = 1; index <= userCount; index++) {
            User user = userRepository.save(User.builder()
                    .username(USER_PREFIX + runId + "_" + index).password(passwordHash).role("USER").build());
            Long couponId = couponIds.get((index - 1) % couponIds.size());
            users.add(new FixtureUser(user.getUsername(), issueAccessToken(runId, user), couponId,
                    DEVICE_PREFIX + runId + "-" + index));
        }
        return users;
    }

    private String issueAccessToken(String runId, User user) {
        String token = randomToken();
        // COSEC: test tokens are random, short-lived Redis sessions and are tracked only by their validated run ID.
        redisTemplate.opsForHash().putAll(tokenService.accessTokenKey(token), Map.of(
                "userId", user.getId().toString(), "username", user.getUsername(), "role", "USER"));
        redisTemplate.expire(tokenService.accessTokenKey(token), TOKEN_TTL);
        stringRedisTemplate.opsForSet().add(tokenIndexKey(runId), token);
        stringRedisTemplate.expire(tokenIndexKey(runId), TRACKER_TTL);
        return token;
    }

    private void ensureNoPendingOrders(Collection<Coupon> coupons) {
        for (Coupon coupon : coupons) {
            List<String> pending = stringRedisTemplate.opsForList().range(SeckillRedisKeys.pending(coupon.getId()), 0, -1);
            if (pending != null && !pending.isEmpty()) {
                throw new IllegalStateException("测试订单仍在异步处理中，请等待队列清空后再清理: couponId=" + coupon.getId());
            }
        }
    }

    private void clearAccessTokens(String runId) {
        Set<String> tokens = stringRedisTemplate.opsForSet().members(tokenIndexKey(runId));
        if (tokens != null) tokens.forEach(token -> redisTemplate.delete(tokenService.accessTokenKey(token)));
        stringRedisTemplate.delete(tokenIndexKey(runId));
    }

    private void ensureAbsent(String runId) {
        if (!couponRepository.findByCouponNameStartingWith(COUPON_PREFIX + runId + "_").isEmpty()
                || !userRepository.findByUsernameStartingWith(USER_PREFIX + runId + "_").isEmpty()) {
            throw new IllegalArgumentException("该 runId 已存在测试夹具，请先完成异步订单后执行清理");
        }
    }

    private int bounded(Integer value, int min, int max, int defaultValue, String field) {
        int result = value == null ? defaultValue : value;
        if (result < min || result > max) throw new IllegalArgumentException(field + " 必须在 " + min + "-" + max + " 之间");
        return result;
    }

    private String requireRunId(String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("runId 仅允许字母、数字、下划线和连字符，长度 1-40");
        }
        return runId;
    }

    private void requireMerchant() {
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("仅商家账号可操作性能测试夹具");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenIndexKey(String runId) {
        return TOKEN_INDEX_PREFIX + runId;
    }

    public record FixtureRequest(String runId, Integer userCount, Integer shopCount,
                                 Integer couponCount, Integer stockPerCoupon) {}
    public record CleanupRequest(String runId) {}
    public record FixtureUser(String username, String accessToken, Long couponId, String deviceFingerprint) {}
    public record FixtureResponse(String runId, List<Long> merchantIds, List<Long> couponIds,
                                  List<FixtureUser> users) {}
}

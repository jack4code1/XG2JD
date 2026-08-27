package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.model.Coupon;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.service.CouponCacheService;
import com.seckill.service.CouponVersionService;
import com.seckill.service.CouponSeckillStateService;
import com.seckill.model.CouponVersion;
import com.seckill.repository.MerchantRepository;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券管理接口（运营后台用）
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final CouponSeckillStateService couponSeckillStateService;
    private final CouponCacheService couponCacheService;
    private final CouponVersionService couponVersionService;

    /**
     * 创建优惠券活动 + 预热到 Redis
     */
    @PostMapping("/create")
    public Result<Coupon> create(@RequestBody Coupon coupon) {
        requireMerchant();
        coupon.setMerchantId(merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId());
        coupon.setRemainStock(coupon.getTotalStock());
        coupon.setStatus(coupon.getStartTime().isAfter(LocalDateTime.now()) ? 0 : 1);
        Coupon saved = couponRepository.save(coupon);

        couponSeckillStateService.initialize(saved);
        couponCacheService.publish(saved);
        couponVersionService.record(saved, "CREATE", UserContext.getUserId());

        return Result.ok(saved);
    }

    /** User-facing activity detail; only static configuration is cached. */
    @GetMapping("/{couponId}")
    public Result<java.util.Map<String, Object>> detail(@PathVariable Long couponId) {
        java.util.Map<String, Object> detail = couponCacheService.getCouponDetail(couponId);
        if (detail == null) throw new IllegalArgumentException("优惠券不存在");
        return Result.ok(detail);
    }

    @GetMapping("/{couponId}/versions")
    public Result<List<CouponVersion>> versions(@PathVariable Long couponId) {
        requireMerchant();
        ownedCoupon(couponId);
        return Result.ok(couponVersionService.history(couponId));
    }

    @GetMapping("/{couponId}/cache-status")
    public Result<java.util.Map<String, Object>> cacheStatus(@PathVariable Long couponId) {
        requireMerchant();
        ownedCoupon(couponId);
        return Result.ok(couponCacheService.cacheStatus(couponId));
    }

    @PostMapping("/{couponId}/rollback/{version}")
    public Result<Coupon> rollback(@PathVariable Long couponId, @PathVariable Integer version) {
        requireMerchant();
        Coupon saved = couponVersionService.rollback(couponId, version,
                ownedCoupon(couponId).getMerchantId(), UserContext.getUserId());
        couponSeckillStateService.initialize(saved);
        couponCacheService.publish(saved);
        return Result.ok(saved);
    }

    /**
     * 查询进行中的活动
     */
    @GetMapping("/active")
    public Result<List<Coupon>> listActive() {
        return Result.ok(couponRepository.findByStatus(1));
    }

    /** 编辑当前商户优惠券，并将库存、状态和时间同步到 Redis。 */
    @PutMapping("/{couponId}")
    @Transactional
    public Result<Coupon> update(@PathVariable Long couponId, @RequestBody ManageRequest request) {
        requireMerchant();
        Coupon coupon = ownedCoupon(couponId);
        if (request.couponName() != null) {
            if (request.couponName().isBlank()) throw new IllegalArgumentException("优惠券名称不能为空");
            coupon.setCouponName(request.couponName().trim());
        }
        if (request.couponDesc() != null) coupon.setCouponDesc(request.couponDesc().trim());
        if (request.discountAmount() != null) {
            if (request.discountAmount().compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("优惠金额不能小于 0");
            coupon.setDiscountAmount(request.discountAmount());
        }
        if (request.perUserMax() != null) {
            if (request.perUserMax() < 1 || request.perUserMax() > 5) throw new IllegalArgumentException("每人限领必须在 1-5 张之间");
            coupon.setPerUserMax(request.perUserMax());
        }
        if (request.endTime() != null) {
            if (!request.endTime().isAfter(LocalDateTime.now())) throw new IllegalArgumentException("结束时间必须晚于当前时间");
            coupon.setEndTime(request.endTime());
        }
        if (request.additionalStock() != null) {
            if (request.additionalStock() < 0 || request.additionalStock() > 5000) throw new IllegalArgumentException("单次追加库存必须在 0-5000 张之间");
            int realtimeRemain = realtimeRemain(coupon);
            coupon.setTotalStock(coupon.getTotalStock() + request.additionalStock());
            coupon.setRemainStock(realtimeRemain + request.additionalStock());
        }
        if (request.status() != null) {
            if (request.status() != 1 && request.status() != 3) throw new IllegalArgumentException("优惠券状态只能是运行或暂停");
            coupon.setStatus(request.status());
        }
        Coupon saved = couponRepository.saveAndFlush(coupon);
        couponSeckillStateService.syncActivity(saved);
        if (request.additionalStock() != null) {
            couponSeckillStateService.replaceStock(saved);
        }
        couponCacheService.publish(saved);
        couponVersionService.record(saved, "UPDATE", UserContext.getUserId());
        return Result.ok(saved);
    }

    public record ManageRequest(String couponName, String couponDesc, BigDecimal discountAmount,
                                Integer perUserMax, LocalDateTime endTime,
                                Integer additionalStock, Integer status) {}

    private Coupon ownedCoupon(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("优惠券不存在"));
        Long merchantId = merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId();
        if (!merchantId.equals(coupon.getMerchantId())) throw new ForbiddenException("无权管理其他商户的优惠券");
        return coupon;
    }

    private int realtimeRemain(Coupon coupon) {
        return couponSeckillStateService.currentStock(coupon.getId(), coupon.getRemainStock());
    }

    private void requireMerchant() {
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("只有商家可以管理优惠券");
        }
    }
}

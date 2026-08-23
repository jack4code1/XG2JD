package com.seckill.scheduler;

import com.seckill.model.Coupon;
import com.seckill.repository.CouponRepository;
import com.seckill.service.CouponCacheService;
import com.seckill.service.CouponVersionService;
import com.seckill.service.NotificationService;
import com.seckill.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Moves scheduled campaigns into ACTIVE and expires active campaigns. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponLifecycleScheduler {
    private final CouponRepository couponRepository;
    private final CouponCacheService couponCacheService;
    private final CouponVersionService couponVersionService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MerchantRepository merchantRepository;
    private final NotificationService notificationService;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void advanceLifecycle() {
        LocalDateTime now = LocalDateTime.now();
        couponRepository.findByStatusAndStartTimeBefore(0, now).forEach(coupon -> transition(coupon, 1, "AUTO_START"));
        couponRepository.findByStatusAndEndTimeBefore(1, now).forEach(coupon -> transition(coupon, 2, "AUTO_END"));
    }

    private void transition(Coupon coupon, int status, String action) {
        coupon.setStatus(status);
        Coupon saved = couponRepository.saveAndFlush(coupon);
        redisTemplate.opsForHash().put("seckill:coupon:" + saved.getId(), "status", saved.getStatus());
        couponCacheService.publish(saved);
        couponVersionService.record(saved, action, null);
        merchantRepository.findById(saved.getMerchantId()).ifPresent(merchant -> notificationService.notify(
                merchant.getUserId(), "CAMPAIGN_" + action,
                "AUTO_START".equals(action) ? "活动已自动开始" : "活动已自动结束",
                "「" + saved.getCouponName() + "」状态已更新"));
        log.info("活动生命周期流转: couponId={}, action={}", saved.getId(), action);
    }
}

package com.seckill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.model.Coupon;
import com.seckill.model.CouponVersion;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.CouponVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CouponVersionService {
    private final CouponVersionRepository versionRepository;
    private final CouponRepository couponRepository;
    private final CouponCacheService couponCacheService;
    private final ObjectMapper objectMapper;

    public void record(Coupon coupon, String action, Long actorId) {
        try {
            versionRepository.save(CouponVersion.builder()
                    .couponId(coupon.getId()).merchantId(coupon.getMerchantId())
                    .versionNo(coupon.getVersion() == null ? 0 : coupon.getVersion())
                    .action(action).createdBy(actorId)
                    .snapshotJson(objectMapper.writeValueAsString(snapshot(coupon))).build());
        } catch (Exception e) {
            throw new IllegalStateException("活动版本快照保存失败", e);
        }
    }

    public List<CouponVersion> history(Long couponId) {
        return versionRepository.findByCouponIdOrderByVersionNoDesc(couponId);
    }

    @Transactional
    public Coupon rollback(Long couponId, Integer versionNo, Long merchantId, Long actorId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new IllegalArgumentException("优惠券不存在"));
        if (!merchantId.equals(coupon.getMerchantId())) throw new IllegalArgumentException("无权回滚其他商户活动");
        CouponVersion target = versionRepository.findByCouponIdAndVersionNo(couponId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("活动版本不存在"));
        try {
            Map<String, Object> data = objectMapper.readValue(target.getSnapshotJson(), new TypeReference<>() {});
            coupon.setCouponName(String.valueOf(data.get("couponName")));
            coupon.setCouponDesc(String.valueOf(data.getOrDefault("couponDesc", "")));
            coupon.setDiscountAmount(new BigDecimal(String.valueOf(data.get("discountAmount"))));
            coupon.setStartTime(LocalDateTime.parse(String.valueOf(data.get("startTime"))));
            coupon.setEndTime(LocalDateTime.parse(String.valueOf(data.get("endTime"))));
            coupon.setPerUserMax(Integer.parseInt(String.valueOf(data.get("perUserMax"))));
            coupon.setStatus(Integer.parseInt(String.valueOf(data.get("status"))));
            Coupon saved = couponRepository.saveAndFlush(coupon);
            couponCacheService.publish(saved);
            record(saved, "ROLLBACK_TO_V" + versionNo, actorId);
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("活动版本回滚失败", e);
        }
    }

    private Map<String, Object> snapshot(Coupon coupon) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponName", coupon.getCouponName()); result.put("couponDesc", coupon.getCouponDesc());
        result.put("discountAmount", coupon.getDiscountAmount()); result.put("startTime", coupon.getStartTime());
        result.put("endTime", coupon.getEndTime()); result.put("perUserMax", coupon.getPerUserMax());
        result.put("status", coupon.getStatus());
        return result;
    }
}

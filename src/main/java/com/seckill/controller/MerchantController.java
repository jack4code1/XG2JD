package com.seckill.controller;

import com.seckill.model.Coupon;
import com.seckill.model.Merchant;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantRepository merchantRepository;
    private final CouponRepository couponRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /** 商家列表（美团风格） */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        return merchantRepository.findAll().stream().map(m -> {
            List<Coupon> coupons = couponRepository.findAll().stream()
                    .filter(c -> c.getMerchantId() != null && c.getMerchantId().equals(m.getId()))
                    .map(this::withRealtimeStock)
                    .toList();
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("shopName", m.getShopName());
            map.put("shopDesc", m.getShopDesc());
            map.put("category", m.getCategory());
            map.put("couponCount", coupons.size());
            map.put("coupons", coupons);
            return map;
        }).collect(Collectors.toList());
    }

    private Coupon withRealtimeStock(Coupon coupon) {
        Object remain = redisTemplate.opsForHash().get("seckill:coupon:" + coupon.getId(), "remain");
        if (remain != null) {
            try {
                coupon.setRemainStock(Integer.valueOf(remain.toString()));
            } catch (NumberFormatException ignored) {
                // Redis 数据异常时保留 MySQL 快照，避免商家列表整体失败。
            }
        }
        return coupon;
    }

    /** 更新店铺信息 */
    @PutMapping("/{id}")
    public Merchant update(@PathVariable Long id, @RequestBody Merchant req) {
        Merchant m = merchantRepository.findById(id).orElseThrow();
        if (req.getShopName() != null) m.setShopName(req.getShopName());
        if (req.getShopDesc() != null) m.setShopDesc(req.getShopDesc());
        if (req.getCategory() != null) m.setCategory(req.getCategory());
        return merchantRepository.save(m);
    }

    /** 查商家的优惠券 */
    @GetMapping("/{id}/coupons")
    public List<Coupon> merchantCoupons(@PathVariable Long id) {
        return couponRepository.findAll().stream()
                .filter(c -> c.getMerchantId() != null && c.getMerchantId().equals(id))
                .toList();
    }
}

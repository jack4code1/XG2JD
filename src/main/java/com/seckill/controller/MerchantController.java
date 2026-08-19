package com.seckill.controller;

import com.seckill.model.Coupon;
import com.seckill.model.Merchant;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.util.UserContext;
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
        List<Merchant> merchants = "MERCHANT".equals(UserContext.getRole())
                ? merchantRepository.findByUserId(UserContext.getUserId()).stream().toList()
                : merchantRepository.findAll();
        return merchants.stream().map(this::toShopView).toList();
    }

    /** 当前登录商户的店铺与优惠券，仅用于商家工作台。 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return toShopView(currentMerchant());
    }

    private Map<String, Object> toShopView(Merchant m) {
            List<Coupon> coupons = couponRepository.findByMerchantIdOrderByCreatedAtDesc(m.getId())
                    .stream().map(this::withRealtimeStock).toList();
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("shopName", m.getShopName());
            map.put("shopDesc", m.getShopDesc());
            map.put("category", m.getCategory());
            map.put("couponCount", coupons.size());
            map.put("coupons", coupons);
            return map;
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
        Merchant m = ownedMerchant(id);
        if (req.getShopName() != null) m.setShopName(req.getShopName());
        if (req.getShopDesc() != null) m.setShopDesc(req.getShopDesc());
        if (req.getCategory() != null) m.setCategory(req.getCategory());
        return merchantRepository.save(m);
    }

    /** 查商家的优惠券 */
    @GetMapping("/{id}/coupons")
    public List<Coupon> merchantCoupons(@PathVariable Long id) {
        return couponRepository.findByMerchantIdOrderByCreatedAtDesc(ownedMerchant(id).getId())
                .stream().map(this::withRealtimeStock).toList();
    }

    private Merchant currentMerchant() {
        if (!"MERCHANT".equals(UserContext.getRole())) {
            throw new ForbiddenException("只有商家可以访问店铺管理数据");
        }
        return merchantRepository.findByUserId(UserContext.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("商家店铺不存在"));
    }

    private Merchant ownedMerchant(Long id) {
        Merchant merchant = currentMerchant();
        if (!merchant.getId().equals(id)) {
            throw new ForbiddenException("无权访问其他商户店铺");
        }
        return merchant;
    }
}

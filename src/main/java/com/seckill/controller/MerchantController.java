package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.model.Coupon;
import com.seckill.model.Merchant;
import com.seckill.model.Product;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.ProductRepository;
import com.seckill.service.CouponSeckillStateService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantRepository merchantRepository;
    private final CouponRepository couponRepository;
    private final CouponSeckillStateService couponSeckillStateService;
    private final ProductRepository productRepository;

    /** 商家列表（美团风格） */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        List<Merchant> merchants = "MERCHANT".equals(UserContext.getRole())
                ? merchantRepository.findByUserId(UserContext.getUserId()).stream().toList()
                : merchantRepository.findAll();
        return Result.ok(merchants.stream().map(this::toShopView).toList());
    }

    /** 当前登录商户的店铺与优惠券，仅用于商家工作台。 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        return Result.ok(toShopView(currentMerchant()));
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
            map.put("products", productRepository.findByMerchantIdOrderByCreatedAtDesc(m.getId()));
            return map;
    }

    private Coupon withRealtimeStock(Coupon coupon) {
        coupon.setRemainStock(couponSeckillStateService.currentStock(coupon.getId(), coupon.getRemainStock()));
        return coupon;
    }

    /** 更新店铺信息 */
    @PutMapping("/{id}")
    public Result<Merchant> update(@PathVariable Long id, @RequestBody Merchant req) {
        Merchant m = ownedMerchant(id);
        if (req.getShopName() != null) m.setShopName(req.getShopName());
        if (req.getShopDesc() != null) m.setShopDesc(req.getShopDesc());
        if (req.getCategory() != null) m.setCategory(req.getCategory());
        return Result.ok(merchantRepository.save(m));
    }

    /** 查商家的优惠券 */
    @GetMapping("/{id}/coupons")
    public Result<List<Coupon>> merchantCoupons(@PathVariable Long id) {
        return Result.ok(couponRepository.findByMerchantIdOrderByCreatedAtDesc(ownedMerchant(id).getId())
                .stream().map(this::withRealtimeStock).toList());
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

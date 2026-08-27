package com.seckill.controller;

import com.seckill.common.Result;
import com.seckill.exception.ForbiddenException;
import com.seckill.model.Coupon;
import com.seckill.model.Order;
import com.seckill.model.Product;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.ProductRepository;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {
    private static final String CLAIM = "COUPON_CLAIM";
    private static final String PURCHASE = "PRODUCT_PURCHASE";
    private final ProductRepository productRepository;
    private final MerchantRepository merchantRepository;
    private final CouponRepository couponRepository;
    private final OrderRepository orderRepository;

    @GetMapping("/shop/{merchantId}")
    public Result<List<Product>> list(@PathVariable Long merchantId) {
        return Result.ok(productRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, 1));
    }

    @PostMapping("/create")
    public Result<Product> create(@RequestBody Product request) {
        MerchantAccess.requireMerchant();
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("商品名称不能为空");
        }
        if (request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("商品价格必须大于 0");
        }
        if (request.getRemainStock() == null || request.getRemainStock() < 0) {
            throw new IllegalArgumentException("商品库存不能小于 0");
        }
        Product product = new Product();
        product.setMerchantId(currentMerchantId());
        product.setName(request.getName().trim());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setRemainStock(request.getRemainStock());
        product.setStatus(1);
        return Result.ok(productRepository.save(product));
    }

    /** 当前商户编辑商品资料、库存和上下架状态。 */
    @PutMapping("/{productId}")
    @Transactional
    public Result<Product> update(@PathVariable Long productId, @RequestBody Product request) {
        MerchantAccess.requireMerchant();
        Product product = ownedProduct(productId);
        if (request.getName() != null) {
            if (request.getName().isBlank()) throw new IllegalArgumentException("商品名称不能为空");
            product.setName(request.getName().trim());
        }
        if (request.getDescription() != null) product.setDescription(request.getDescription().trim());
        if (request.getPrice() != null) {
            if (request.getPrice().compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("商品价格必须大于 0");
            product.setPrice(request.getPrice());
        }
        if (request.getRemainStock() != null) {
            if (request.getRemainStock() < 0) throw new IllegalArgumentException("商品库存不能小于 0");
            product.setRemainStock(request.getRemainStock());
        }
        if (request.getStatus() != null) {
            if (request.getStatus() != 0 && request.getStatus() != 1) throw new IllegalArgumentException("商品状态只能是上架或下架");
            product.setStatus(request.getStatus());
        }
        return Result.ok(productRepository.save(product));
    }

    @PostMapping("/{productId}/purchase")
    @Transactional
    public Result<Map<String, Object>> purchase(@PathVariable Long productId, @RequestBody(required = false) Map<String, Long> body) {
        if (!"USER".equals(UserContext.getRole())) throw new ForbiddenException("只有普通用户可以购买商品");
        Product product = productRepository.findById(productId).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        if (product.getStatus() != 1 || product.getRemainStock() == null || product.getRemainStock() < 1) {
            throw new IllegalArgumentException("商品已售罄或已下架");
        }
        Long couponId = body == null ? null : body.get("couponId");
        BigDecimal discount = BigDecimal.ZERO;
        if (couponId != null) {
            Coupon coupon = couponRepository.findById(couponId).orElseThrow(() -> new IllegalArgumentException("代金券不存在"));
            if (!product.getMerchantId().equals(coupon.getMerchantId())) throw new ForbiddenException("代金券不属于该店铺");
            if (!couponInWindow(coupon)) throw new IllegalArgumentException("代金券当前不可用");
            if (!orderRepository.existsByUserIdAndCouponIdAndOrderType(UserContext.getUserId(), couponId, CLAIM)) {
                throw new IllegalArgumentException("你还没有领取这张代金券");
            }
            if (orderRepository.existsByUserIdAndCouponIdAndOrderTypeAndStatusIn(UserContext.getUserId(), couponId, PURCHASE, List.of("PENDING_PAYMENT", "PAYING", "PAID", "USED"))) {
                throw new IllegalArgumentException("这张代金券已经使用过");
            }
            discount = coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount();
            if (discount.compareTo(product.getPrice()) > 0) discount = product.getPrice();
        }
        if (productRepository.decrementRemainStock(productId) != 1) throw new IllegalArgumentException("商品库存不足");
        Order order = new Order();
        order.setOrderNo(System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 8));
        order.setUserId(UserContext.getUserId());
        order.setProductId(productId);
        order.setCouponId(couponId);
        order.setOrderType(PURCHASE);
        order.setOriginalAmount(product.getPrice());
        order.setDiscountAmount(discount);
        order.setAmount(product.getPrice().subtract(discount));
        order.setStatus("PENDING_PAYMENT");
        order.setVersion(0);
        Order saved = orderRepository.save(order);
        return Result.ok(Map.of("orderNo", saved.getOrderNo(), "originalAmount", product.getPrice(), "discountAmount", discount, "payableAmount", saved.getAmount()));
    }

    private boolean couponInWindow(Coupon c) {
        return c.getStatus() == 1 && !java.time.LocalDateTime.now().isBefore(c.getStartTime()) && java.time.LocalDateTime.now().isBefore(c.getEndTime());
    }

    private Long currentMerchantId() { return merchantRepository.findByUserId(UserContext.getUserId()).orElseThrow(() -> new IllegalArgumentException("商家店铺不存在")).getId(); }

    private Product ownedProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        if (!currentMerchantId().equals(product.getMerchantId())) throw new ForbiddenException("无权管理其他商户的商品");
        return product;
    }

    private static class MerchantAccess {
        static void requireMerchant() { if (!"MERCHANT".equals(UserContext.getRole())) throw new ForbiddenException("只有商家可以管理商品"); }
    }
}

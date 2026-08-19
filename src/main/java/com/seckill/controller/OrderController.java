package com.seckill.controller;

import com.seckill.model.Order;
import com.seckill.model.Coupon;
import com.seckill.dto.OrderView;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.ProductRepository;
import com.seckill.service.OrderService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单管理接口
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final MerchantRepository merchantRepository;
    private final ProductRepository productRepository;

    /** 支付订单 */
    @PostMapping("/{orderNo}/pay")
    public Map<String, Object> pay(@PathVariable String orderNo) {
        requireOwner(orderNo);
        if (!orderService.pay(orderNo)) {
            throw new IllegalArgumentException("订单当前状态不可支付");
        }
        return Map.of("success", true, "orderNo", orderNo, "status", "PAYING", "message", "已创建支付单");
    }

    /** 模拟第三方支付回调，生产环境应由支付网关异步调用。 */
    @PostMapping("/{orderNo}/pay/confirm")
    public Map<String, Object> confirmPayment(@PathVariable String orderNo) {
        requireOwner(orderNo);
        if (!orderService.paySuccess(orderNo)) {
            throw new IllegalArgumentException("支付单已失效或订单不可确认");
        }
        return Map.of("success", true, "orderNo", orderNo, "status", "PAID", "message", "支付成功");
    }

    /** 取消订单 */
    @PostMapping("/{orderNo}/cancel")
    public String cancel(@PathVariable String orderNo) {
        requireOwner(orderNo);
        return orderService.cancel(orderNo) ? "ok" : "fail";
    }

    /** 退款 */
    @PostMapping("/{orderNo}/refund")
    public String refund(@PathVariable String orderNo) {
        requireOwner(orderNo);
        return orderService.refund(orderNo) ? "ok" : "fail";
    }

    /** 查询订单 */
    @GetMapping("/{orderNo}")
    public Order query(@PathVariable String orderNo) {
        return requireOwner(orderNo);
    }

    /** 查询用户订单列表 */
    @GetMapping("/user/{userId}")
    public List<Order> listByUser(@PathVariable Long userId, @RequestParam Long couponId) {
        if (!userId.equals(UserContext.getUserId())) {
            throw new ForbiddenException("无权查询该用户订单");
        }
        return orderRepository.findByUserIdAndCouponId(userId, couponId);
    }

    /** 查询当前登录用户的全部订单，避免前端暴露或伪造 userId。 */
    @GetMapping("/user")
    public List<OrderView> listCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return List.of();
        }
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    @GetMapping("/user/coupons")
    public List<java.util.Map<String, Object>> usableCoupons() {
        Long userId = UserContext.getUserId();
        return orderRepository.findByUserIdAndOrderType(userId, "COUPON_CLAIM").stream()
                .map(Order::getCouponId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(couponRepository::findById)
                .flatMap(java.util.Optional::stream)
                .filter(c -> orderRepository.existsByUserIdAndCouponIdAndOrderTypeAndStatusIn(userId, c.getId(), "COUPON_CLAIM", List.of("CREATED", "PAID", "USED")))
                .filter(c -> !orderRepository.existsByUserIdAndCouponIdAndOrderTypeAndStatusIn(userId, c.getId(), "PRODUCT_PURCHASE", List.of("CREATED", "PAYING", "PAID", "USED")))
                .map(c -> {
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("id", c.getId());
                    result.put("name", c.getCouponName());
                    result.put("discountAmount", c.getDiscountAmount() == null ? java.math.BigDecimal.ZERO : c.getDiscountAmount());
                    result.put("merchantId", c.getMerchantId());
                    return result;
                })
                .toList();
    }

    private Order requireOwner(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (!order.getUserId().equals(UserContext.getUserId())) {
            throw new ForbiddenException("无权操作该订单");
        }
        return order;
    }

    private OrderView toView(Order order) {
        Coupon coupon = order.getCouponId() == null ? null : couponRepository.findById(order.getCouponId()).orElse(null);
        String shopName = "-";
        if (coupon != null && coupon.getMerchantId() != null) {
            shopName = merchantRepository.findById(coupon.getMerchantId())
                    .map(m -> m.getShopName())
                    .orElse("-");
        }
        return OrderView.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .couponId(order.getCouponId())
                .productId(order.getProductId())
                .couponName(coupon != null ? coupon.getCouponName() : null)
                .productName(order.getProductId() == null ? null : productRepository.findById(order.getProductId()).map(p -> p.getName()).orElse(null))
                .shopName(shopName)
                .status(order.getStatus())
                .amount(order.getAmount())
                .originalAmount(order.getOriginalAmount())
                .discountAmount(order.getDiscountAmount())
                .orderType(order.getOrderType())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}

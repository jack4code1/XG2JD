package com.seckill.controller;

import com.seckill.model.Order;
import com.seckill.model.Coupon;
import com.seckill.dto.OrderView;
import com.seckill.exception.ForbiddenException;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.MerchantRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.service.OrderService;
import com.seckill.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 支付订单 */
    @PostMapping("/{orderNo}/pay")
    public String pay(@PathVariable String orderNo) {
        requireOwner(orderNo);
        return orderService.pay(orderNo) ? "ok" : "fail";
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

    private Order requireOwner(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (!order.getUserId().equals(UserContext.getUserId())) {
            throw new ForbiddenException("无权操作该订单");
        }
        return order;
    }

    private OrderView toView(Order order) {
        Coupon coupon = couponRepository.findById(order.getCouponId()).orElse(null);
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
                .couponName(coupon != null ? coupon.getCouponName() : null)
                .shopName(shopName)
                .status(order.getStatus())
                .amount(order.getAmount())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}

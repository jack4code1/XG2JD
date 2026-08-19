package com.seckill.controller;

import com.seckill.model.Order;
import com.seckill.repository.OrderRepository;
import com.seckill.service.OrderService;
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

    /** 支付订单 */
    @PostMapping("/{orderNo}/pay")
    public String pay(@PathVariable String orderNo) {
        return orderService.pay(orderNo) ? "ok" : "fail";
    }

    /** 取消订单 */
    @PostMapping("/{orderNo}/cancel")
    public String cancel(@PathVariable String orderNo) {
        return orderService.cancel(orderNo) ? "ok" : "fail";
    }

    /** 退款 */
    @PostMapping("/{orderNo}/refund")
    public String refund(@PathVariable String orderNo) {
        return orderService.refund(orderNo) ? "ok" : "fail";
    }

    /** 查询订单 */
    @GetMapping("/{orderNo}")
    public Order query(@PathVariable String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElse(null);
    }

    /** 查询用户订单列表 */
    @GetMapping("/user/{userId}")
    public List<Order> listByUser(@PathVariable Long userId, @RequestParam Long couponId) {
        return orderRepository.findByUserIdAndCouponId(userId, couponId);
    }
}
package com.seckill.service;

import com.seckill.model.OrderStatus;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.ProductRepository;
import com.seckill.model.EventLog;
import com.seckill.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单服务 — 状态机 + 乐观锁
 *
 * 设计说明：
 * - 每次状态变更都校验 OrderStatus.canTransitionTo()
 * - 乐观锁 UPDATE ... WHERE version=?，并发冲突→重试
 * - 退款/取消 → 同时执行 Lua 原子回滚库存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final EventLogRepository eventLogRepository;
    private final CouponRepository couponRepository;
    private final CouponSeckillStateService couponSeckillStateService;
    private final ProductRepository productRepository;

    /**
     * 用户支付订单：PENDING_PAYMENT → PAYING
     */
    @Transactional
    public boolean pay(String orderNo) {
        Order order = find(orderNo);
        if (!"PRODUCT_PURCHASE".equals(order.getOrderType())) {
            throw new IllegalArgumentException("代金券领取不需要支付，请用于商品结算");
        }
        return transition(orderNo, "PENDING_PAYMENT", "PAYING");
    }

    /**
     * 支付回调成功：PAYING → PAID
     */
    @Transactional
    public boolean paySuccess(String orderNo) {
        Order order = find(orderNo);
        if (!"PRODUCT_PURCHASE".equals(order.getOrderType())) {
            throw new IllegalArgumentException("代金券领取不需要支付");
        }
        return transition(orderNo, "PAYING", "PAID");
    }

    /**
     * 取消订单：待支付 → CANCELED
     */
    @Transactional
    public boolean cancel(String orderNo) {
        Order order = find(orderNo);
        String expected = "PRODUCT_PURCHASE".equals(order.getOrderType()) ? "PENDING_PAYMENT" : "CREATED";
        boolean changed = transition(orderNo, expected, "CANCELED");
        if (changed) restoreStock(orderNo);
        return changed;
    }

    /**
     * 退款申请：PAID → REFUNDING
     */
    @Transactional
    public boolean refund(String orderNo) {
        return transition(orderNo, "PAID", "REFUNDING");
    }

    /**
     * 退款完成：REFUNDING → REFUNDED
     */
    @Transactional
    public boolean refundSuccess(String orderNo) {
        return transition(orderNo, "REFUNDING", "REFUNDED");
    }

    /**
     * 核销优惠券：PAID → USED
     */
    @Transactional
    public boolean use(String orderNo) {
        return transition(orderNo, "PAID", "USED");
    }

    /**
     * 通用状态转移（乐观锁版本号）
     */
    private boolean transition(String orderNo, String expectedStatus, String targetStatus) {
        OrderStatus.validateTransition(expectedStatus, targetStatus);

        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));

        if (!expectedStatus.equals(order.getStatus())) {
            log.warn("状态转移冲突: orderNo={}, expected={}, actual={}",
                    orderNo, expectedStatus, order.getStatus());
            return false;
        }

        order.setStatus(targetStatus);
        // JPA @Version 自动处理乐观锁：WHERE version = ?
        // 并发冲突时抛出 OptimisticLockException
        orderRepository.save(order);

        // 写入本地消息表（同一事务）
        EventLog eventLog = EventLog.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ORDER_" + targetStatus)
                .aggregateId(orderNo)
                .payload("{\"orderNo\":\"" + orderNo + "\",\"from\":\"" + expectedStatus +
                        "\",\"to\":\"" + targetStatus + "\"}")
                .status(0)
                .retryCount(0)
                .maxRetry(10)
                .nextRetryAt(LocalDateTime.now())
                .build();
        eventLogRepository.save(eventLog);

        log.info("订单状态转移: orderNo={}, {}→{}", orderNo, expectedStatus, targetStatus);
        return true;
    }

    @Transactional
    public void restoreStock(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        if ("COUPON_CLAIM".equals(order.getOrderType())) {
            if (couponRepository.incrementRemainStock(order.getCouponId()) != 1) {
                throw new IllegalStateException("MySQL 券库存回补失败: couponId=" + order.getCouponId());
            }
            Long restored = couponSeckillStateService.restoreStock(order.getCouponId());
            if (restored == null) throw new IllegalStateException("Redis 券库存回补失败: couponId=" + order.getCouponId());
        } else if (order.getProductId() != null && productRepository.incrementRemainStock(order.getProductId()) != 1) {
            throw new IllegalStateException("商品库存回补失败: productId=" + order.getProductId());
        }
        log.info("订单库存已回补: orderNo={}, type={}", orderNo, order.getOrderType());
    }

    private Order find(String orderNo) {
        return orderRepository.findByOrderNo(orderNo).orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
    }
}

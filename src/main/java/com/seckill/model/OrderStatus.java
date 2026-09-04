package com.seckill.model;

import java.util.Set;

/**
 * 订单状态枚举 — 7状态 + 转移规则
 *
 * 设计说明：
 * - 用枚举替代 Spring State Machine（太重）
 * - 每个状态自带 allowedTransitions，状态转移规则内聚
 * - 状态流转只依赖状态自身，不依赖外部配置
 */
public enum OrderStatus {

    /** Coupon claims retain CREATED; product purchases start at PENDING_PAYMENT. */
    CREATED(Set.of("CANCELED", "EXPIRED")),
    PENDING_PAYMENT(Set.of("PAYING", "CANCELED", "EXPIRED")),
    PAYING(Set.of("PAID", "CANCELED")),
    PAID(Set.of("USED", "REFUNDING")),
    USED(Set.of()),
    REFUNDING(Set.of("REFUNDED")),
    REFUNDED(Set.of()),
    CANCELED(Set.of()),
    EXPIRED(Set.of());

    private final Set<String> allowedTransitions;

    OrderStatus(Set<String> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    /**
     * 判断是否可以转移到目标状态
     */
    public boolean canTransitionTo(OrderStatus target) {
        return allowedTransitions.contains(target.name());
    }

    /**
     * 执行状态转移（带校验）
     */
    public static void validateTransition(String from, String to) {
        OrderStatus fromStatus = valueOf(from);
        OrderStatus toStatus = valueOf(to);
        if (!fromStatus.canTransitionTo(toStatus)) {
            throw new IllegalStateException(
                    String.format("非法状态转移: %s → %s", from, to));
        }
    }
}

package com.seckill.strategy;

/**
 * 优惠券分配策略接口 — 策略模式
 * 设计说明：策略模式解耦分配逻辑，新增策略无需修改核心代码。
 */
public interface AllocationStrategy {

    /**
     * 计算用户分配权重
     * @param userId 用户ID
     * @param couponId 优惠券ID
     * @return 权重值（>0提高优先级，<0降低优先级，0表示不适用此策略）
     */
    int calculateWeight(Long userId, Long couponId);

    /**
     * 策略优先级（越小越先执行）
     */
    int getPriority();

    /**
     * 策略名称，用于日志与管理界面展示。
     */
    String getName();
}

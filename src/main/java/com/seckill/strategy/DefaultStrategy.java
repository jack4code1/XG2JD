package com.seckill.strategy;

import org.springframework.stereotype.Component;

/**
 * 默认策略：先到先得兜底
 */
@Component
public class DefaultStrategy implements AllocationStrategy {

    @Override
    public int calculateWeight(Long userId, Long couponId) {
        return 100; // 基础权重
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE; // 最低优先级，最后执行
    }

    @Override
    public String getName() {
        return "默认先到先得策略";
    }
}
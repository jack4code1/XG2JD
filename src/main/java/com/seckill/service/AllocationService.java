package com.seckill.service;

import com.seckill.strategy.AllocationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 智能分配策略编排服务
 *
 * 面试可讲：
 * - Spring 自动注入所有 AllocationStrategy 实现类
 * - 按 priority 排序：防黄牛(0) → 新用户(1) → 沉睡用户(2) → 默认(MAX)
 * - 权重链累加：baseWeight(100) + newUserBonus(50) + dormantBonus(30) - scalperPenalty(∞)
 * - 新增策略只需添加 @Component 实现类，零侵入
 */
@Slf4j
@Service
public class AllocationService {

    private final List<AllocationStrategy> strategies;
    private static final int BASE_WEIGHT = 100;

    public AllocationService(List<AllocationStrategy> strategies) {
        // 按优先级排序注入
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(AllocationStrategy::getPriority))
                .toList();
    }

    /**
     * 计算用户最终分配权重
     * @return 权重值，<=0 表示无资格参与
     */
    public int calculateFinalWeight(Long userId, Long couponId) {
        int totalWeight = BASE_WEIGHT;

        for (AllocationStrategy strategy : strategies) {
            int weight = strategy.calculateWeight(userId, couponId);

            // 防黄牛策略返回 Integer.MIN_VALUE → 直接拒绝
            if (weight <= Integer.MIN_VALUE / 2) {
                log.info("用户被策略拒绝: userId={}, strategy={}", userId, strategy.getName());
                return 0;
            }

            if (weight != 0) {
                log.debug("策略命中: userId={}, strategy={}, weight={}", userId, strategy.getName(), weight);
                totalWeight += weight;
            }
        }

        return Math.max(totalWeight, 0);
    }
}
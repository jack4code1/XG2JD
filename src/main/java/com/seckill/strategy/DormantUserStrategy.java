package com.seckill.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 沉睡用户唤醒策略：30天未活跃用户权重 +30
 *
 * 设计说明：
 * - 用 Redis Set 记录每日活跃用户（user:active:{date}），TTL 32天
 * - 检查近30天 Set 中是否有该用户 → 都没有 = 沉睡用户
 * - 相比查 MySQL 用户表 last_login_at，Redis O(1) 性能高一个数量级
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DormantUserStrategy implements AllocationStrategy {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public int calculateWeight(Long userId, Long couponId) {
        // 检查近30天是否有活跃记录
        for (int i = 1; i <= 30; i++) {
            String date = LocalDate.now().minusDays(i).format(DF);
            String key = "user:active:" + date;
            Boolean active = redisTemplate.opsForSet().isMember(key, userId.toString());
            if (Boolean.TRUE.equals(active)) {
                return 0; // 有活跃记录，不是沉睡用户
            }
        }
        log.debug("沉睡用户权重命中: userId={}", userId);
        return 30;
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public String getName() {
        return "沉睡用户唤醒策略";
    }
}

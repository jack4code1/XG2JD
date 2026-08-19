package com.seckill.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 新用户权重策略：注册不足7天的用户权重 +50
 *
 * 面试可讲：
 * - 使用 Redis BitMap 存储用户画像，1亿用户仅需12.5MB
 * - 按天分片（user:new:2024-01-01），TTL 8天自动清理
 * - BitMap offset = userId % hashCode，避免用户ID不连续问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewUserStrategy implements AllocationStrategy {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public int calculateWeight(Long userId, Long couponId) {
        // 检查最近7天是否有该用户的注册记录
        for (int i = 0; i < 7; i++) {
            String date = LocalDate.now().minusDays(i).format(DF);
            String key = "user:new:" + date;
            Boolean isNew = redisTemplate.opsForSet().isMember(key, userId.toString());
            if (Boolean.TRUE.equals(isNew)) {
                log.debug("新用户权重命中: userId={}, date={}", userId, date);
                return 50;
            }
        }
        return 0;
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getName() {
        return "新用户权重策略";
    }
}
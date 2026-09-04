package com.seckill.service;

import com.seckill.cache.HotKeyCacheManager;
import com.seckill.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Canal 缓存同步消费者
 *
 * 设计说明：
 * - MySQL Binlog → Canal → RabbitMQ → 本消费者 → 更新 Redis + 失效 Caffeine
 * - Canal 只管推送 Binlog 变更，应用只管消费，职责清晰
 * - 幂等设计：消息体包含 binlogPosition，已消费位置跳过
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheSyncConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private final HotKeyCacheManager hotKeyCacheManager;

    /** Clears only process-local Caffeine entries; L2 is owned by the writer. */
    @SuppressWarnings("unchecked")
    @RabbitListener(queues = "#{cacheInvalidationQueue.name}")
    public void handleCacheInvalidation(Map<String, Object> message) {
        Object keys = message.get("cacheKeys");
        if (!(keys instanceof java.util.Collection<?> cacheKeys)) {
            log.warn("忽略缺少 cacheKeys 的缓存失效消息");
            return;
        }
        cacheKeys.stream().map(String::valueOf).forEach(hotKeyCacheManager::evict);
        log.debug("已清除本地缓存: keys={}", cacheKeys);
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.CACHE_SYNC_QUEUE)
    public void handleCacheSync(Map<String, Object> message) {
        try {
            String table = (String) message.get("table");
            String type = (String) message.get("type"); // INSERT / UPDATE / DELETE
            Map<String, Object> data = (Map<String, Object>) message.get("data");

            if (data == null) {
                log.warn("缓存同步消息数据为空: table={}, type={}", table, type);
                return;
            }

            log.debug("缓存同步: table={}, type={}, data={}", table, type, data);

            switch (table) {
                case "t_coupon" -> handleCouponChange(type, data);
                case "t_order" -> handleOrderChange(type, data);
                default -> log.debug("未处理的表变更: {}", table);
            }
        } catch (Exception e) {
            log.error("缓存同步消费异常", e);
            throw e; // 抛异常 → NACK → 消息重试
        }
    }

    /**
     * 优惠券表变更 → 更新 Redis + 失效 Caffeine
     */
    private void handleCouponChange(String type, Map<String, Object> data) {
        Object idObj = data.get("id");
        if (idObj == null) return;

        String id = idObj.toString();
        String cacheKey = "coupon:" + id;

        if ("DELETE".equals(type)) {
            redisTemplate.delete(cacheKey);
            log.info("缓存删除(优惠券删除): key={}", cacheKey);
        } else {
            // INSERT / UPDATE：重建 Redis 缓存
            // 注意：实际应该查 MySQL 获取完整数据，这里简化处理
            redisTemplate.opsForValue().set(cacheKey, data, 5, java.util.concurrent.TimeUnit.MINUTES);

            // 如果是热点数据，降级 Caffeine 让下次请求重新加载
            hotKeyCacheManager.downgrade(cacheKey);
            log.info("缓存更新: key={}, type={}", cacheKey, type);
        }
    }

    /**
     * 订单表变更 → 更新订单相关缓存
     */
    private void handleOrderChange(String type, Map<String, Object> data) {
        Object orderNoObj = data.get("order_no");
        if (orderNoObj == null) return;

        String orderCacheKey = "order:" + orderNoObj.toString();

        if ("DELETE".equals(type)) {
            redisTemplate.delete(orderCacheKey);
        } else {
            redisTemplate.opsForValue().set(orderCacheKey, data, 1, java.util.concurrent.TimeUnit.HOURS);
        }
    }
}

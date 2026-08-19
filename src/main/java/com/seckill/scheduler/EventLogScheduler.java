package com.seckill.scheduler;

import com.seckill.model.EventLog;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表扫描调度器 — 指数退避重试
 *
 * 面试可讲：
 * - 定时扫 event_log 表（每秒），捞待发送消息批量投递
 * - 指数退避：1s → 2s → 4s → 8s → 16s → 30s（封顶）
 * - 超过 max_retry(10次) → status=3 终态 → 人工介入告警
 * - 对比 RocketMQ 事务消息：本地消息表更通用，不绑定 MQ 实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLogScheduler {

    private final EventLogRepository eventLogRepository;
    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;
    private final CouponRepository couponRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${seckill.order.expire-minutes:15}")
    private int orderExpireMinutes;

    /**
     * 每秒扫描待发送事件
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void scanPendingEvents() {
        List<EventLog> pendingEvents = eventLogRepository
                .findByStatusAndNextRetryAtBefore(0, LocalDateTime.now());

        for (EventLog event : pendingEvents) {
            try {
                // 投递到 MQ
                rabbitTemplate.convertAndSend("order.event", event.getEventType(), event.getPayload());
                // 标记已发送
                event.setStatus(1);
                eventLogRepository.save(event);
                log.debug("事件投递成功: eventId={}, type={}", event.getEventId(), event.getEventType());
            } catch (Exception e) {
                handleRetry(event);
            }
        }
    }

    /**
     * 指数退避重试
     */
    private void handleRetry(EventLog event) {
        int retryCount = event.getRetryCount() + 1;
        event.setRetryCount(retryCount);

        if (retryCount >= event.getMaxRetry()) {
            event.setStatus(3); // 失败终态
            log.error("事件发送失败终态: eventId={}, type={}, retryCount={}",
                    event.getEventId(), event.getEventType(), retryCount);
        } else {
            // 指数退避：1s, 2s, 4s, 8s, 16s, 32s, 64s, ... 最大 30s
            long delay = Math.min((long) Math.pow(2, retryCount - 1), 30);
            event.setNextRetryAt(LocalDateTime.now().plusSeconds(delay));
            log.warn("事件发送失败，将重试: eventId={}, retryCount={}, delay={}s",
                    event.getEventId(), retryCount, delay);
        }
        eventLogRepository.save(event);
    }

    /**
     * 每分钟扫描过期订单
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireOrders() {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(orderExpireMinutes);
        List<com.seckill.model.Order> orders = orderRepository
                .findByStatusAndCreatedAtBefore("CREATED", expireTime);
        for (com.seckill.model.Order order : orders) {
            order.setStatus("EXPIRED");
            orderRepository.save(order);
            if (couponRepository.incrementRemainStock(order.getCouponId()) == 1) {
                redisTemplate.opsForHash().increment(
                        "seckill:coupon:" + order.getCouponId(), "remain", 1);
            }
        }
        int count = orders.size();
        if (count > 0) {
            log.info("过期订单处理完成: count={}, expireTime={}", count, expireTime);
        }
    }
}

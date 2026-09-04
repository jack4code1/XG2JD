package com.seckill.service;

import com.seckill.config.RabbitMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.model.EventLog;
import com.seckill.perf.PerfOrderConsumerMetrics;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.OrderRepository;
import com.seckill.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ 消费者：异步创建订单
 *
 * 设计说明：
 * - 监听 order.create.queue，批量拉取（prefetch=1 防止单消费者积压）
 * - 订单表 + 本地消息表 在同一事务中写入
 * - 写入失败 → NACK → 消息重试 → 超过重试次数 → 死信队列兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreateConsumer {

    private final OrderRepository orderRepository;
    private final EventLogRepository eventLogRepository;
    private final CouponRepository couponRepository;
    private final PendingOrderService pendingOrderService;
    private final ObjectProvider<PerfOrderConsumerMetrics> perfOrderConsumerMetrics;

    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATE_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCreate(OrderMessage message) {
        long startedAt = System.nanoTime();
        PerfOrderConsumerMetrics.Outcome outcome = PerfOrderConsumerMetrics.Outcome.FAILED;
        try {
        log.info("收到订单创建消息: orderNo={}, userId={}, couponId={}",
                message.getOrderNo(), message.getUserId(), message.getCouponId());

        // 1. The INSERT itself is the concurrency-safe idempotency gate.
        int inserted = orderRepository.insertCouponClaimIfAbsent(message.getOrderNo(), message.getUserId(),
                message.getCouponId(), message.getAmount() != null ? message.getAmount() : java.math.BigDecimal.ZERO);
        if (inserted == 0) {
            log.warn("订单已存在，跳过: orderNo={}", message.getOrderNo());
            pendingOrderService.acknowledgeAfterCommit(message.getOrderNo(), message.getCouponId());
            outcome = PerfOrderConsumerMetrics.Outcome.DUPLICATE;
            return;
        }
        if (inserted != 1) throw new IllegalStateException("订单幂等写入返回异常: " + inserted);

        // Redis 是秒杀实时库存，订单落库后同步 MySQL 展示库存，保证商家端和数据库最终一致。
        if (couponRepository.decrementRemainStock(message.getCouponId()) != 1) {
            throw new IllegalStateException("优惠券库存同步失败: couponId=" + message.getCouponId());
        }

        // 3. 写入本地消息表
        EventLog eventLog = new EventLog();
        eventLog.setEventId(UUID.randomUUID().toString());
        eventLog.setEventType("ORDER_CREATED");
        eventLog.setAggregateId(message.getOrderNo());
        eventLog.setPayload("{\"orderNo\":\"" + message.getOrderNo() + "\"}");
        eventLog.setStatus(0);
        eventLog.setRetryCount(0);
        eventLog.setMaxRetry(10);
        eventLog.setNextRetryAt(LocalDateTime.now());
        eventLogRepository.saveAndFlush(eventLog);

        // Do not clear pending before this transaction commits. A crash after
        // MQ confirm but before commit is recovered by the next idempotent replay.
        pendingOrderService.acknowledgeAfterCommit(message.getOrderNo(), message.getCouponId());

        log.info("订单创建成功: orderNo={}", message.getOrderNo());
        outcome = PerfOrderConsumerMetrics.Outcome.CREATED;
        } finally {
            // Perf-only observer: it times the actual consumer method without changing acknowledgements.
            PerfOrderConsumerMetrics.Outcome observedOutcome = outcome;
            perfOrderConsumerMetrics.ifAvailable(metrics -> metrics.record(observedOutcome, System.nanoTime() - startedAt));
        }
    }
}

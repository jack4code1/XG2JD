package com.seckill.service;

import com.seckill.config.RabbitMQConfig;
import com.seckill.dto.OrderMessage;
import com.seckill.model.EventLog;
import com.seckill.model.Order;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ 消费者：异步创建订单
 *
 * 面试可讲：
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

    @RabbitListener(queues = RabbitMQConfig.ORDER_CREATE_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCreate(OrderMessage message) {
        log.info("收到订单创建消息: orderNo={}, userId={}, couponId={}",
                message.getOrderNo(), message.getUserId(), message.getCouponId());

        // 1. 幂等检查
        if (orderRepository.findByOrderNo(message.getOrderNo()).isPresent()) {
            log.warn("订单已存在，跳过: orderNo={}", message.getOrderNo());
            return;
        }

        // 2. 写入订单表
        Order order = new Order();
        order.setOrderNo(message.getOrderNo());
        order.setUserId(message.getUserId());
        order.setCouponId(message.getCouponId());
        order.setStatus("CREATED");
        order.setAmount(message.getAmount() != null ? message.getAmount() : java.math.BigDecimal.ZERO);
        order.setVersion(0);
        orderRepository.saveAndFlush(order);

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

        log.info("订单创建成功: orderNo={}", message.getOrderNo());
    }
}
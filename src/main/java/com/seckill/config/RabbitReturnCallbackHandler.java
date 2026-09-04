package com.seckill.config;

import com.seckill.service.PendingOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Converts an unroutable publish into a recoverable pending-order retry. */
@Slf4j
@Component
public class RabbitReturnCallbackHandler {

    public RabbitReturnCallbackHandler(RabbitTemplate rabbitTemplate, PendingOrderService pendingOrderService) {
        rabbitTemplate.setReturnsCallback(returned -> {
            String orderNo = returned.getMessage().getMessageProperties().getCorrelationId();
            if (orderNo == null || orderNo.isBlank()) {
                log.error("RabbitMQ returned an order message without correlationId: replyCode={}, replyText={}",
                        returned.getReplyCode(), returned.getReplyText());
                return;
            }
            boolean scheduled = pendingOrderService.markDeliveryReturned(orderNo);
            log.warn("RabbitMQ returned unroutable order message: orderNo={}, replyCode={}, retryScheduled={}",
                    orderNo, returned.getReplyCode(), scheduled);
        });
    }
}

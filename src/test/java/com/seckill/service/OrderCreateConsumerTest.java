package com.seckill.service;

import com.seckill.dto.OrderMessage;
import com.seckill.repository.CouponRepository;
import com.seckill.repository.EventLogRepository;
import com.seckill.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCreateConsumerTest {

    @Test
    void normalDeliveryCreatesOrderThenAppliesSideEffectsOnce() {
        OrderRepository orders = mock(OrderRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        EventLogRepository events = mock(EventLogRepository.class);
        PendingOrderService pending = mock(PendingOrderService.class);
        when(orders.insertCouponClaimIfAbsent(eq("o-1"), eq(7L), eq(8L), any())).thenReturn(1);
        when(coupons.decrementRemainStock(8L)).thenReturn(1);

        consumer(orders, coupons, events, pending).handleOrderCreate(message("o-1"));

        verify(coupons).decrementRemainStock(8L);
        verify(events).saveAndFlush(any());
        verify(pending).acknowledgeAfterCommit("o-1", 8L);
    }

    @Test
    void duplicateDeliveryDoesNotRepeatDatabaseSideEffects() {
        OrderRepository orders = mock(OrderRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        EventLogRepository events = mock(EventLogRepository.class);
        PendingOrderService pending = mock(PendingOrderService.class);
        when(orders.insertCouponClaimIfAbsent(eq("o-2"), eq(7L), eq(8L), any())).thenReturn(0);

        consumer(orders, coupons, events, pending).handleOrderCreate(message("o-2"));

        verify(coupons, never()).decrementRemainStock(any());
        verify(events, never()).saveAndFlush(any());
        verify(pending).acknowledgeAfterCommit("o-2", 8L);
    }

    @Test
    void rabbitRedeliveryIsIdempotentAcrossTwoConsumerInvocations() {
        OrderRepository orders = mock(OrderRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        EventLogRepository events = mock(EventLogRepository.class);
        PendingOrderService pending = mock(PendingOrderService.class);
        when(orders.insertCouponClaimIfAbsent(eq("o-3"), eq(7L), eq(8L), any())).thenReturn(1, 0);
        when(coupons.decrementRemainStock(8L)).thenReturn(1);
        OrderCreateConsumer consumer = consumer(orders, coupons, events, pending);

        consumer.handleOrderCreate(message("o-3"));
        consumer.handleOrderCreate(message("o-3"));

        verify(coupons, times(1)).decrementRemainStock(8L);
        verify(events, times(1)).saveAndFlush(any());
        verify(pending, times(2)).acknowledgeAfterCommit("o-3", 8L);
    }

    @Test
    void genuineConsumerFailureDoesNotAcknowledgePending() {
        OrderRepository orders = mock(OrderRepository.class);
        CouponRepository coupons = mock(CouponRepository.class);
        EventLogRepository events = mock(EventLogRepository.class);
        PendingOrderService pending = mock(PendingOrderService.class);
        when(orders.insertCouponClaimIfAbsent(eq("o-4"), eq(7L), eq(8L), any())).thenReturn(1);
        when(coupons.decrementRemainStock(8L)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> consumer(orders, coupons, events, pending)
                .handleOrderCreate(message("o-4")));

        verify(pending, never()).acknowledgeAfterCommit("o-4", 8L);
        verify(events, never()).saveAndFlush(any());
    }

    @SuppressWarnings("unchecked")
    private OrderCreateConsumer consumer(OrderRepository orders, CouponRepository coupons,
                                         EventLogRepository events, PendingOrderService pending) {
        ObjectProvider<com.seckill.perf.PerfOrderConsumerMetrics> metrics = mock(ObjectProvider.class);
        return new OrderCreateConsumer(orders, events, coupons, pending, metrics);
    }

    private OrderMessage message(String orderNo) {
        return OrderMessage.builder().orderNo(orderNo).userId(7L).couponId(8L)
                .amount(BigDecimal.ZERO).userWeight(1).timestamp(1L).build();
    }
}

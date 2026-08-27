package com.seckill.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderStatusTest {

    @Test
    void pendingPaymentCanBePaidOrCanceled() {
        assertDoesNotThrow(() -> OrderStatus.validateTransition("PENDING_PAYMENT", "PAYING"));
        assertDoesNotThrow(() -> OrderStatus.validateTransition("CREATED", "CANCELED"));
    }

    @Test
    void paidCannotBeCanceled() {
        assertThrows(IllegalStateException.class,
                () -> OrderStatus.validateTransition("PAID", "CANCELED"));
    }
}

package com.example.settlement.payment.application.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.settlement.payment.domain.OrderId;

class PaymentAuthorizedTest {

    @Test
    void holdsTheGivenOrderId() {
        OrderId orderId = new OrderId(UUID.randomUUID());

        PaymentAuthorized event = new PaymentAuthorized(orderId);

        assertEquals(orderId, event.orderId());
    }

    @Test
    void rejectsNullOrderId() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentAuthorized(null));
    }
}

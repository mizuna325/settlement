package com.example.settlement.payment.application.port.out;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.settlement.payment.domain.OrderId;

class PaymentAuthDeclinedTest {

    @Test
    void holdsTheGivenOrderId() {
        OrderId orderId = new OrderId(UUID.randomUUID());

        PaymentAuthDeclined event = new PaymentAuthDeclined(orderId);

        assertEquals(orderId, event.orderId());
    }

    @Test
    void rejectsNullOrderId() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentAuthDeclined(null));
    }
}

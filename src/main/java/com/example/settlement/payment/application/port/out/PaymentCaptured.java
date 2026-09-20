package com.example.settlement.payment.application.port.out;

import com.example.settlement.payment.domain.OrderId;

/** 売上確定が完了したことの通知(REQ-ORD-004)。 */
public record PaymentCaptured(OrderId orderId) {
    public PaymentCaptured {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
    }
}

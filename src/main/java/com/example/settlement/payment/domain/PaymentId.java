package com.example.settlement.payment.domain;

import java.util.UUID;

public record PaymentId(UUID paymentId) {
    public PaymentId {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null.");
        }
    }

    public static PaymentId generate() {
        return new PaymentId(UUID.randomUUID());
    }
}

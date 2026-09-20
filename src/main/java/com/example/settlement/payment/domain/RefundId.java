package com.example.settlement.payment.domain;

import java.util.UUID;

public record RefundId(UUID refundId) {
    public RefundId {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId must not be null.");
        }
    }

    public static RefundId generate() {
        return new RefundId(UUID.randomUUID());
    }
}

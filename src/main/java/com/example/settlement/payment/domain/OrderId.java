package com.example.settlement.payment.domain;

import java.util.UUID;

public record OrderId(UUID orderId) {
    public OrderId {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
    }
}

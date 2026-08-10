package com.example.settlement.order.domain;

import java.util.UUID;

public record OrderId(UUID orderId) {
    public OrderId {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
    }

    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
    }
}

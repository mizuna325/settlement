package com.example.settlement.order.domain;

import java.util.UUID;

public record CustomerId(UUID customerId) {
    public CustomerId {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId must not be null.");
        }
    }

}

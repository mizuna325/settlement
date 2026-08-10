package com.example.settlement.order.domain;

public record ProductId(String productId) {
    public ProductId {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null.");
        }
        if (productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be blank.");
        }
    }
}

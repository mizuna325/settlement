package com.example.settlement.order.domain;

public record Quantity(int quantity) {
    public Quantity {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be 1 or greater.");
        }
    }
}

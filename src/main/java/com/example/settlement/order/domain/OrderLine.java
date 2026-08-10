package com.example.settlement.order.domain;

import com.example.settlement.shared.Money;

public record OrderLine(ProductId productId, Quantity quantity, Money amount) {
    public OrderLine {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null.");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("quantity must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
    }
}

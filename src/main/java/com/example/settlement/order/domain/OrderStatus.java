package com.example.settlement.order.domain;

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SETTLED,
    PARTIALLY_REFUNDED,
    CANCELLED,
    SETTLEMENT_FAILED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == SETTLED || next == SETTLEMENT_FAILED;
            case SETTLED -> next == PARTIALLY_REFUNDED || next == REFUNDED;
            case PARTIALLY_REFUNDED -> next == PARTIALLY_REFUNDED || next == REFUNDED;
            case CANCELLED, SETTLEMENT_FAILED, REFUNDED -> false;

        };
    }
}

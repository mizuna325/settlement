package com.example.settlement.payment.domain;

public enum RefundStatus {
    PENDING,
    REFUNDED,
    FAILED;

    public boolean canTransitionTo(RefundStatus next) {
        return switch (this) {
            case PENDING -> next == REFUNDED || next == FAILED;
            case REFUNDED, FAILED -> false;
        };
    }
}

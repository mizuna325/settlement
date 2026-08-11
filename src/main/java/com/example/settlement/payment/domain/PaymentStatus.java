package com.example.settlement.payment.domain;

public enum PaymentStatus {
    AUTHORIZING,
    AUTHORIZED,
    AUTH_DECLINED,
    CAPTURING,
    CAPTURED,
    CAPTURE_FAILED,
    REFUNDING,
    PARTIALLY_REFUNDED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case AUTHORIZING -> next == AUTHORIZED || next == AUTH_DECLINED;
            case AUTHORIZED -> next == CAPTURING;
            case CAPTURING -> next == CAPTURED || next == CAPTURE_FAILED;
            case CAPTURED -> next == REFUNDING;
            case REFUNDING -> next == REFUNDED || next == PARTIALLY_REFUNDED || next == CAPTURED;
            case PARTIALLY_REFUNDED -> next == REFUNDING;
            case AUTH_DECLINED, CAPTURE_FAILED, REFUNDED -> false;
        };
    }
}

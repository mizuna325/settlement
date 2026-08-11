package com.example.settlement.payment.domain;

public enum AuthorizationStatus {
    PENDING,
    AUTHORIZED,
    DECLINED;

    public boolean canTransitionTo(AuthorizationStatus next) {
        return switch (this) {
            case PENDING -> next == AUTHORIZED || next == DECLINED;
            case AUTHORIZED, DECLINED -> false;
        };
    }
}

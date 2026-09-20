package com.example.settlement.payment.domain;

public enum CaptureStatus {
    PENDING,
    CAPTURED,
    FAILED;

    public boolean canTransitionTo(CaptureStatus next) {
        return switch (this) {
            case PENDING -> next == CAPTURED || next == FAILED;
            case CAPTURED, FAILED -> false;
        };
    }
}

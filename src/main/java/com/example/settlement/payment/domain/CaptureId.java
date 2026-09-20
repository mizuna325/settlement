package com.example.settlement.payment.domain;

import java.util.UUID;

public record CaptureId(UUID captureId) {
    public CaptureId {
        if (captureId == null) {
            throw new IllegalArgumentException("captureId must not be null.");
        }
    }

    public static CaptureId generate() {
        return new CaptureId(UUID.randomUUID());
    }
}

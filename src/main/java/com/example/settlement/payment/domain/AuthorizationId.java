package com.example.settlement.payment.domain;

import java.util.UUID;

public record AuthorizationId(UUID authorizationId) {
    public AuthorizationId {
        if (authorizationId == null) {
            throw new IllegalArgumentException("authorizationId must not be null.");
        }
    }
}

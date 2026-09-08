package com.example.settlement.payment.application.port.out;

import java.time.Instant;

public interface WebhookEventStorePort {
    boolean registerIfAbsent(String eventId, Instant receivedAt);

}
package com.example.settlement.payment.adapter.out.idempotency;

import com.example.settlement.payment.application.port.out.WebhookEventStorePort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class WebhookEventJdbcStore implements WebhookEventStorePort {

    private final JdbcClient jdbcClient;

    WebhookEventJdbcStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean registerIfAbsent(String eventId, Instant receivedAt) {
        int inserted = jdbcClient.sql("""
                INSERT INTO payment_webhook_events (event_id, received_at)
                VALUES (:eventId, :receivedAt)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("eventId", eventId)
                .param("receivedAt", OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

}

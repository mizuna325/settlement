package com.example.settlement.payment.adapter.out.idempotency;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

@Component
class WebhookEventCleaner {
    private final WebhookEventProperty webhookEventProperty;
    private final JdbcClient jdbcClient;
    private final Clock clock;

    WebhookEventCleaner(WebhookEventProperty webhookEventProperty, JdbcClient jdbcClient, Clock clock) {
        this.webhookEventProperty = webhookEventProperty;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    int deleteExpired() {
        return jdbcClient.sql("""
                DELETE FROM payment_webhook_events WHERE received_at < :threshold
                """)
                .param("threshold",
                        OffsetDateTime.ofInstant(
                                this.clock.instant().minus(this.webhookEventProperty.webhookEventRetention()),
                                ZoneOffset.UTC))
                .update();

    }

}

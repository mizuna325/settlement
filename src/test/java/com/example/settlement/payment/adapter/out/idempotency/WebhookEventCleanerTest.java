package com.example.settlement.payment.adapter.out.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQ-NFR-007: 受信済み eventId の保持期間(30日)を過ぎた行を削除する。
 *
 * <p>
 * 消しすぎると、遅れて届いた再送を初回と誤判定して二重に適用してしまう。
 * 残しすぎると、業務上参照されない行が際限なく積み上がる。両側を確かめる。
 *
 * <p>
 * テストのトランザクションで実行し、終了時にロールバックする。
 */
@SpringBootTest
@Transactional
class WebhookEventCleanerTest {

    /** 既定の保持期間。application.properties の settlement.psp.webhook-event-retention と揃える。 */
    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    WebhookEventCleaner webhookEventCleaner;

    @Autowired
    JdbcClient jdbcClient;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private String insert(Instant receivedAt) {
        String eventId = "evt-" + UUID.randomUUID();
        jdbcClient.sql("INSERT INTO payment_webhook_events (event_id, received_at) VALUES (:id, :receivedAt)")
                .param("id", eventId)
                .param("receivedAt", OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                .update();
        return eventId;
    }

    private long countOf(String eventId) {
        return jdbcClient.sql("SELECT count(*) FROM payment_webhook_events WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("REQ-NFR-007: 保持期間を過ぎた受信記録は削除される")
    void expiredEventsAreDeleted() {
        String expired = insert(now.minus(RETENTION).minus(Duration.ofDays(1)));

        webhookEventCleaner.deleteExpired();

        assertEquals(0L, countOf(expired));
    }

    @Test
    @DisplayName("REQ-NFR-007: 保持期間内の受信記録は残る")
    void recentEventsAreKept() {
        String recent = insert(now.minus(RETENTION).plus(Duration.ofDays(1)));

        webhookEventCleaner.deleteExpired();

        assertEquals(1L, countOf(recent));
    }

    @Test
    @DisplayName("削除は保持期間を過ぎた行だけを対象にする")
    void onlyExpiredRowsAreAffected() {
        String expired = insert(now.minus(Duration.ofDays(100)));
        String recent = insert(now);

        webhookEventCleaner.deleteExpired();

        assertEquals(0L, countOf(expired));
        assertEquals(1L, countOf(recent));
    }
}

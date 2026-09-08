package com.example.settlement.payment.adapter.out.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.payment.application.port.out.WebhookEventStorePort;

/**
 * 受信側の冪等性(REQ-PSP-006)を実DBに対して確認する。
 *
 * <p>
 * テストのトランザクションで実行し、終了時にロールバックする。event_id は
 * テストごとに採番するため、他のテストが残した行とは衝突しない。
 */
@SpringBootTest
@Transactional
class WebhookEventJdbcStoreTest {

    @Autowired
    WebhookEventStorePort webhookEventStorePort;

    @Autowired
    JdbcClient jdbcClient;

    /** TIMESTAMPTZ はマイクロ秒までしか保持しないため、比較できるよう切り詰めておく。 */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private static String eventId() {
        return "evt-" + UUID.randomUUID();
    }

    private long countOf(String eventId) {
        return jdbcClient.sql("SELECT count(*) FROM payment_webhook_events WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single();
    }

    private Instant receivedAtOf(String eventId) {
        return jdbcClient.sql("SELECT received_at FROM payment_webhook_events WHERE event_id = :id")
                .param("id", eventId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    @Test
    @DisplayName("REQ-PSP-006: 初めて受け取った eventId は記録され true を返す")
    void firstEventIsRecorded() {
        String eventId = eventId();

        assertTrue(webhookEventStorePort.registerIfAbsent(eventId, now));

        assertEquals(1L, countOf(eventId));
    }

    @Test
    @DisplayName("REQ-PSP-006: 同じ eventId の2回目は false を返す")
    void duplicateEventIsRejected() {
        String eventId = eventId();
        webhookEventStorePort.registerIfAbsent(eventId, now);

        assertFalse(webhookEventStorePort.registerIfAbsent(eventId, now.plusSeconds(60)));
    }

    @Test
    @DisplayName("重複しても行は増えず、初回の受信時刻が保たれる")
    void duplicateDoesNotOverwriteTheFirstRecord() {
        String eventId = eventId();
        webhookEventStorePort.registerIfAbsent(eventId, now);

        webhookEventStorePort.registerIfAbsent(eventId, now.plusSeconds(60));

        assertEquals(1L, countOf(eventId));
        assertEquals(now, receivedAtOf(eventId));
    }

    @Test
    @DisplayName("別の eventId は互いに影響しない")
    void differentEventsAreIndependent() {
        String first = eventId();
        String second = eventId();

        assertTrue(webhookEventStorePort.registerIfAbsent(first, now));
        assertTrue(webhookEventStorePort.registerIfAbsent(second, now));
    }
}

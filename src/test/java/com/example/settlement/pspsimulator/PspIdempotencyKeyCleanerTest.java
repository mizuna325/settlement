package com.example.settlement.pspsimulator;

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
 * REQ-NFR-006: PSP側の冪等性キーの保持期間(24時間)を過ぎた行を削除する。
 *
 * <p>
 * キーが残っていることが、取り残された行を回収して再送してよい根拠になっている
 * (design.md §5.1)。消しすぎると回収時に重複と判定されず二重決済になるため、
 * 保持期間は claimTimeout(1分)より十分長く取っている。
 *
 * <p>
 * payment 側の {@code WebhookEventCleaner} と同じ形だが、モジュールが異なるため
 * 共通化しない(ステップ3の決定事項)。
 */
@SpringBootTest
@Transactional
class PspIdempotencyKeyCleanerTest {

    /** 既定の保持期間。application.properties の settlement.pspsimulator.idempotency-key-retention と揃える。 */
    private static final Duration RETENTION = Duration.ofHours(24);

    @Autowired
    PspIdempotencyKeyCleaner pspIdempotencyKeyCleaner;

    @Autowired
    JdbcClient jdbcClient;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private UUID insert(Instant createdAt) {
        UUID dispatchEventId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO payment_psp_idempotency_keys (dispatch_event_id, created_at)
                VALUES (:id, :createdAt)
                """)
                .param("id", dispatchEventId)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
        return dispatchEventId;
    }

    private long countOf(UUID dispatchEventId) {
        return jdbcClient.sql("SELECT count(*) FROM payment_psp_idempotency_keys WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("REQ-NFR-006: 保持期間を過ぎた冪等性キーは削除される")
    void expiredKeysAreDeleted() {
        UUID expired = insert(now.minus(RETENTION).minus(Duration.ofHours(1)));

        pspIdempotencyKeyCleaner.deleteExpired();

        assertEquals(0L, countOf(expired));
    }

    @Test
    @DisplayName("REQ-NFR-006: 保持期間内の冪等性キーは残る")
    void recentKeysAreKept() {
        UUID recent = insert(now.minus(RETENTION).plus(Duration.ofHours(1)));

        pspIdempotencyKeyCleaner.deleteExpired();

        assertEquals(1L, countOf(recent));
    }

    @Test
    @DisplayName("REQ-NFR-009: 回収が起きる時間(1分)のキーは必ず残っている")
    void keysSurviveLongEnoughForReclaiming() {
        UUID justClaimed = insert(now.minus(Duration.ofMinutes(1)));

        pspIdempotencyKeyCleaner.deleteExpired();

        assertEquals(1L, countOf(justClaimed));
    }
}

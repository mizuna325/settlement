package com.example.settlement.payment.adapter.out.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * PspDispatchStore のSQLを実DBに対して確認する(design.md §5.1)。
 *
 * テストのトランザクションで実行し、終了時にロールバックする。走査は
 * テーブル全体を対象にするため、他のテストが残した行が混ざらないよう
 * 各テストの冒頭で消す(この削除もロールバックされる)。
 */
@SpringBootTest
@Transactional
class PspDispatchStoreTest {

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(1);

    @Autowired
    PspDispatchStore pspDispatchStore;

    @Autowired
    JdbcClient jdbcClient;

    /** TIMESTAMPTZ はマイクロ秒までしか保持しないため、比較できるよう切り詰めておく。 */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void clearDispatchEvents() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
    }

    @Test
    @DisplayName("REQ-PSP-002: 送信時刻を過ぎた PENDING の行を確保し、SENDING・claimed_at・attempts+1 にする")
    void claimsPendingRow() {
        UUID paymentId = UUID.randomUUID();
        UUID dispatchEventId = insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1),
                now.minusSeconds(10), paymentId);

        List<PspDispatchEventEntity> claimed = pspDispatchStore.claim(10, CLAIM_TIMEOUT);

        assertEquals(1, claimed.size());
        PspDispatchEventEntity event = claimed.get(0);

        // マッピングの取り違えを検出する。Instant の列が3つ並んでいるため型では守れない。
        assertEquals(dispatchEventId, event.dispatchEventId());
        assertEquals(paymentId, event.paymentId());
        assertEquals("AUTHORIZE", event.operation());
        assertEquals(1000L, event.amount());
        assertEquals("JPY", event.currency());
        assertEquals(now.minusSeconds(1), event.nextAttemptAt());
        assertEquals(now.minusSeconds(10), event.createdAt());

        // 確保による更新が RETURNING に反映されている。
        assertEquals(PspDispatchStatus.SENDING.name(), event.status());
        assertEquals((short) 1, event.attempts());
        assertNotNull(event.claimedAt());
        assertEquals(PspDispatchStatus.SENDING.name(), statusOf(dispatchEventId));
    }

    @Test
    @DisplayName("REQ-NFR-002: next_attempt_at が未来の行はバックオフ中なので確保しない")
    void skipsRowWaitingForBackoff() {
        insert(PspDispatchStatus.PENDING, (short) 1, now.minusSeconds(30), now.plusSeconds(30), now.minusSeconds(60));

        assertTrue(pspDispatchStore.claim(10, CLAIM_TIMEOUT).isEmpty());
    }

    @Test
    @DisplayName("REQ-NFR-009: claimTimeout を過ぎた SENDING の行は回収する")
    void reclaimsStaleSendingRow() {
        UUID dispatchEventId = insert(PspDispatchStatus.SENDING, (short) 1, now.minus(Duration.ofMinutes(5)),
                now.minusSeconds(300), now.minusSeconds(300));

        List<PspDispatchEventEntity> claimed = pspDispatchStore.claim(10, CLAIM_TIMEOUT);

        assertEquals(1, claimed.size());
        assertEquals(dispatchEventId, claimed.get(0).dispatchEventId());
        // 回収でも attempts は進む。毎回落ちる行が無限に再試行されないため。
        assertEquals((short) 2, claimed.get(0).attempts());
    }

    @Test
    @DisplayName("REQ-NFR-009: claimTimeout 内の SENDING の行は送信中なので確保しない")
    void skipsSendingRowWithinTimeout() {
        insert(PspDispatchStatus.SENDING, (short) 1, now.minusSeconds(5), now.minusSeconds(10), now.minusSeconds(10));

        assertTrue(pspDispatchStore.claim(10, CLAIM_TIMEOUT).isEmpty());
    }

    @Test
    @DisplayName("REQ-NFR-010: created_at の古い順に batchSize 件まで確保する")
    void claimsOldestUpToBatchSize() {
        UUID oldest = insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1), now.minusSeconds(300));
        UUID middle = insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1), now.minusSeconds(200));
        insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1), now.minusSeconds(100));

        List<PspDispatchEventEntity> claimed = pspDispatchStore.claim(2, CLAIM_TIMEOUT);

        assertEquals(List.of(oldest, middle), claimed.stream().map(PspDispatchEventEntity::dispatchEventId).toList());
    }

    @Test
    @DisplayName("REQ-PSP-002: markSent は確保済みの行だけを SENT にする")
    void markSentUpdatesClaimedRow() {
        UUID claimed = insert(PspDispatchStatus.SENDING, (short) 1, now, now.minusSeconds(10), now.minusSeconds(10));
        UUID notClaimed = insert(PspDispatchStatus.PENDING, (short) 0, null, now, now.minusSeconds(10));

        pspDispatchStore.markSent(claimed);
        pspDispatchStore.markSent(notClaimed);

        assertEquals(PspDispatchStatus.SENT.name(), statusOf(claimed));
        assertEquals(PspDispatchStatus.PENDING.name(), statusOf(notClaimed));
    }

    @Test
    @DisplayName("REQ-NFR-002: scheduleRetry は PENDING へ戻し、next_attempt_at を先送りする")
    void scheduleRetryReleasesRow() {
        UUID dispatchEventId = insert(PspDispatchStatus.SENDING, (short) 1, now, now.minusSeconds(10),
                now.minusSeconds(10));
        Instant nextAttemptAt = now.plusSeconds(2);

        pspDispatchStore.scheduleRetry(dispatchEventId, nextAttemptAt);

        assertEquals(PspDispatchStatus.PENDING.name(), statusOf(dispatchEventId));
        assertEquals(nextAttemptAt, nextAttemptAtOf(dispatchEventId));
        // 先送りしたので、この時点では走査に拾われない。
        assertTrue(pspDispatchStore.claim(10, CLAIM_TIMEOUT).isEmpty());
    }

    @Test
    @DisplayName("REQ-PSP-004: markFailed で終端になり、以降は走査に拾われない")
    void markFailedIsTerminal() {
        UUID dispatchEventId = insert(PspDispatchStatus.SENDING, (short) 5, now, now.minusSeconds(10),
                now.minusSeconds(10));

        pspDispatchStore.markFailed(dispatchEventId);

        assertEquals(PspDispatchStatus.FAILED.name(), statusOf(dispatchEventId));
        assertTrue(pspDispatchStore.claim(10, Duration.ofMillis(1)).isEmpty());
    }

    private UUID insert(PspDispatchStatus status, short attempts, Instant claimedAt, Instant nextAttemptAt,
            Instant createdAt) {
        return insert(status, attempts, claimedAt, nextAttemptAt, createdAt, UUID.randomUUID());
    }

    private UUID insert(PspDispatchStatus status, short attempts, Instant claimedAt, Instant nextAttemptAt,
            Instant createdAt, UUID paymentId) {
        UUID dispatchEventId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO payment_psp_dispatch_events
                    (dispatch_event_id, payment_id, operation, amount, currency,
                     status, attempts, claimed_at, next_attempt_at, created_at)
                VALUES (:dispatchEventId, :paymentId, 'AUTHORIZE', 1000, 'JPY',
                        :status, :attempts, :claimedAt, :nextAttemptAt, :createdAt)
                """)
                .param("dispatchEventId", dispatchEventId)
                .param("paymentId", paymentId)
                .param("status", status.name())
                .param("attempts", attempts)
                .param("claimedAt", offsetOf(claimedAt))
                .param("nextAttemptAt", offsetOf(nextAttemptAt))
                .param("createdAt", offsetOf(createdAt))
                .update();
        return dispatchEventId;
    }

    private String statusOf(UUID dispatchEventId) {
        return jdbcClient.sql("SELECT status FROM payment_psp_dispatch_events WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(String.class)
                .single();
    }

    private Instant nextAttemptAtOf(UUID dispatchEventId) {
        return jdbcClient.sql("SELECT next_attempt_at FROM payment_psp_dispatch_events WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private static OffsetDateTime offsetOf(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

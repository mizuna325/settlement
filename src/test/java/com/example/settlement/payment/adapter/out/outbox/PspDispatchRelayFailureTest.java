package com.example.settlement.payment.adapter.out.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PSPへ到達できない場合の PspDispatchRelay を確認する。
 *
 * <p>
 * base-url を待ち受けのないポートへ向ける。PspClient はコンストラクタで base-url を読むため
 * テストメソッドごとには変えられず、成功系の {@link PspDispatchRelayTest} とはクラスを分ける。
 * ポート1は特権ポートで何も listen していないため、タイムアウトを待たず即座に接続拒否になる。
 *
 * <p>
 * backoff-base を0にしている。実際の値(1秒)のままだと 1+2+4 秒の実時間を待つことになり、
 * ここで確かめたいのは待ち時間そのものではなく試行回数の打ち切り(REQ-PSP-004)だから。
 * バックオフで行が確保されなくなることは PspDispatchStoreTest が押さえている。
 */
@SpringBootTest(properties = {
        "settlement.psp.base-url=http://localhost:1",
        // 走査は relay() を直接呼ぶ。enabled=false で消えるのは
        // PspDispatchScheduler だけなので、Relay のBeanは注入できる(design.md §9.1)。
        "settlement.psp.dispatch.enabled=false",
        "settlement.psp.dispatch.backoff-base=0s",
        "settlement.psp.dispatch.max-attempts=3" })
class PspDispatchRelayFailureTest {

    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    PspDispatchRelay pspDispatchRelay;

    @Autowired
    PspDispatchStore pspDispatchStore;

    @Autowired
    JdbcClient jdbcClient;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    @AfterEach
    void clearDispatchEvents() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
    }

    @Test
    @DisplayName("REQ-PSP-004 / REQ-NFR-002: 試行上限を超えたディスパッチは FAILED になり以降拾われない")
    void exceedingMaxAttemptsBecomesFailed() {
        UUID dispatchEventId = insertPending();

        // 1周ごとに1回送信する。attempts は確保時に加算されるので、maxAttempts 周で上限に達する。
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            pspDispatchRelay.relay();
        }

        assertEquals(PspDispatchStatus.FAILED.name(), statusOf(dispatchEventId));
        assertEquals((short) MAX_ATTEMPTS, attemptsOf(dispatchEventId));

        // FAILED は終端。走査クエリの対象外なので、以降は自動で再送されない。
        assertTrue(pspDispatchStore.claim(10, Duration.ofMillis(1)).isEmpty());
    }

    @Test
    @DisplayName("REQ-NFR-002: 上限に達するまでは PENDING へ戻され、next_attempt_at が先送りされる")
    void failureBeforeLimitSchedulesRetry() {
        UUID dispatchEventId = insertPending();

        pspDispatchRelay.relay();

        assertEquals(PspDispatchStatus.PENDING.name(), statusOf(dispatchEventId));
        // 行を確保したまま待たずに手放している。Relay のスレッドを塞がないため。
        assertTrue(nextAttemptAtOf(dispatchEventId).isAfter(now));
    }

    @Test
    @DisplayName("REQ-PSP-001: 確保と送信は別トランザクションで行われる")
    void claimAndSendAreSeparateTransactions() {
        UUID dispatchEventId = insertPending();

        pspDispatchRelay.relay();

        // 送信は失敗したのに attempts の加算が残っている。確保(トランザクション1)が
        // 送信の結果とは無関係に確定している証拠。同一トランザクションなら0に戻る。
        // これが崩れると、HTTP応答待ちの間ずっと行ロックを保持する構造になる。
        assertEquals((short) 1, attemptsOf(dispatchEventId));
    }

    private UUID insertPending() {
        UUID dispatchEventId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO payment_psp_dispatch_events
                    (dispatch_event_id, payment_id, operation, amount, currency,
                     status, attempts, claimed_at, next_attempt_at, created_at)
                VALUES (:dispatchEventId, :paymentId, 'AUTHORIZE', 1000, 'JPY',
                        :status, 0, NULL, :nextAttemptAt, :createdAt)
                """)
                .param("dispatchEventId", dispatchEventId)
                .param("paymentId", UUID.randomUUID())
                .param("status", PspDispatchStatus.PENDING.name())
                .param("nextAttemptAt", offsetOf(now.minusSeconds(1)))
                .param("createdAt", offsetOf(now.minusSeconds(300)))
                .update();
        return dispatchEventId;
    }

    private String statusOf(UUID dispatchEventId) {
        return jdbcClient.sql("SELECT status FROM payment_psp_dispatch_events WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(String.class)
                .single();
    }

    private short attemptsOf(UUID dispatchEventId) {
        return jdbcClient.sql("SELECT attempts FROM payment_psp_dispatch_events WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(Short.class)
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
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

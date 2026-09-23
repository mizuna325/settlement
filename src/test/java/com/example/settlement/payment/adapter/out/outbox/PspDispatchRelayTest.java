package com.example.settlement.payment.adapter.out.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * PspDispatchRelay の結合テスト。PSPシミュレータへ実際にHTTPで送る。
 *
 * <p>
 * PSPが応答する場合のみを扱う。送信が失敗する側は
 * {@link PspDispatchRelayFailureTest} にある。base-url は PspClient が
 * コンストラクタで読むため、テストメソッドごとには変えられない。
 *
 * <p>
 * テスト側に @Transactional を張らない。②の送信はTomcatのスレッドで別トランザクションとして
 * 走るため、テストのトランザクションに巻き込めない。代わりに前後で明示的に消す。
 *
 * <p>
 * Relay の走査は止めたまま relay() を直接呼ぶ。@Scheduled が裏で回ると、
 * 用意した行をテスト本体より先に拾って非決定的になる。ただし enabled=false では
 * Bean 自体が生成されない(@ConditionalOnProperty)ので、enabled=true にしたうえで
 * polling-interval を十分長くする。fixedDelay は初回を即座に実行するが、
 * それはコンテキスト起動時であり、行を用意する前なので何も拾わない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "settlement.psp.dispatch.enabled=true",
        "settlement.psp.dispatch.polling-interval=1h" })
class PspDispatchRelayTest {

    /** 固定の8080だと開発中のアプリと衝突するため、空きポートを取ってから起動する。 */
    private static final int PORT = freePort();

    @DynamicPropertySource
    static void pspEndpoint(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("settlement.psp.base-url", () -> "http://localhost:" + PORT);
    }

    @Autowired
    PspDispatchRelay pspDispatchRelay;

    @Autowired
    JdbcClient jdbcClient;

    /** TIMESTAMPTZ はマイクロ秒までしか保持しないため、比較できるよう切り詰めておく。 */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
        jdbcClient.sql("DELETE FROM payment_psp_idempotency_keys").update();
    }

    @Test
    @DisplayName("REQ-PSP-002: 未送信のディスパッチは PENDING → SENDING → SENT と進む")
    void pendingDispatchIsSentToPsp() {
        UUID dispatchEventId = insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1));

        pspDispatchRelay.relay();

        assertEquals(PspDispatchStatus.SENT.name(), statusOf(dispatchEventId));
        // PSP側にキーが残っていることが「実際に届いた」ことの根拠。
        // status だけではDBの更新しか見ておらず、送信を素通りしても通ってしまう。
        assertEquals(1L, idempotencyKeyCount(dispatchEventId));
    }

    @Test
    @DisplayName("REQ-PSP-003: リトライ時も同一の dispatch_event_id が Idempotency-Key として送られる")
    void reusesSameIdempotencyKeyOnRetry() {
        UUID dispatchEventId = insert(PspDispatchStatus.PENDING, (short) 0, null, now.minusSeconds(1));

        pspDispatchRelay.relay();

        // PSPには届いたが、結果を記録する前にプロセスが落ちた状況を作る。
        // 行は SENDING のまま取り残され、claimTimeout 経過後に回収される。
        jdbcClient.sql("""
                UPDATE payment_psp_dispatch_events
                SET status = :sending, claimed_at = :staleClaimedAt
                WHERE dispatch_event_id = :id
                """)
                .param("sending", PspDispatchStatus.SENDING.name())
                .param("staleClaimedAt", offsetOf(now.minus(Duration.ofMinutes(5))))
                .param("id", dispatchEventId)
                .update();

        pspDispatchRelay.relay();

        assertEquals(PspDispatchStatus.SENT.name(), statusOf(dispatchEventId));
        // 2回送ってもキーは1件。同じ値を送り直しているためPSPが重複と判定している。
        assertEquals(1L, idempotencyKeyCount(dispatchEventId));
    }

    @Test
    @DisplayName("REQ-NFR-009: claimTimeout を過ぎた SENDING の行は再び走査対象になり、WARNが残る")
    void staleSendingRowIsReclaimed() {
        UUID dispatchEventId = insert(PspDispatchStatus.SENDING, (short) 1,
                now.minus(Duration.ofMinutes(5)), now.minusSeconds(300));

        ListAppender<ILoggingEvent> logs = captureLogsOf(PspDispatchStore.class);
        try {
            pspDispatchRelay.relay();
        } finally {
            detach(PspDispatchStore.class, logs);
        }

        assertEquals(PspDispatchStatus.SENT.name(), statusOf(dispatchEventId));
        // 回収でも attempts は進む。毎回落ちる行が無限に再試行されないため。
        assertEquals((short) 2, attemptsOf(dispatchEventId));

        // 回収は運用上の異常であり、黙って進めてはならない(design.md §5.1)。
        // 対象のIDはメッセージ本文ではなく key-value のフィールドに載る(design.md §8.8)。
        // 本文は固定文字列なので、そちらを検索しても行を特定できない。
        assertTrue(logs.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getKeyValuePairs() != null
                        && event.getKeyValuePairs().stream()
                                .anyMatch(pair -> "dispatchEventId".equals(pair.key)
                                        && dispatchEventId.toString().equals(pair.value))),
                "回収を知らせるWARNログが出ていない");
    }

    private UUID insert(PspDispatchStatus status, short attempts, Instant claimedAt, Instant nextAttemptAt) {
        UUID dispatchEventId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO payment_psp_dispatch_events
                    (dispatch_event_id, payment_id, operation, amount, currency,
                     status, attempts, claimed_at, next_attempt_at, created_at)
                VALUES (:dispatchEventId, :paymentId, 'AUTHORIZE', 1000, 'JPY',
                        :status, :attempts, :claimedAt, :nextAttemptAt, :createdAt)
                """)
                .param("dispatchEventId", dispatchEventId)
                .param("paymentId", UUID.randomUUID())
                .param("status", status.name())
                .param("attempts", attempts)
                .param("claimedAt", offsetOf(claimedAt))
                .param("nextAttemptAt", offsetOf(nextAttemptAt))
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

    /** PSP側に記録された冪等性キーの件数。dispatch_event_id をそのままキーとして送っている。 */
    private long idempotencyKeyCount(UUID dispatchEventId) {
        return jdbcClient
                .sql("SELECT count(*) FROM payment_psp_idempotency_keys WHERE dispatch_event_id = :id")
                .param("id", dispatchEventId)
                .query(Long.class)
                .single();
    }

    private static ListAppender<ILoggingEvent> captureLogsOf(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    private static OffsetDateTime offsetOf(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.example.settlement.payment.adapter.out.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentOperation;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Outbox の行を跨いでトレースが繋がることを確認する(design.md §8.1 の境界①)。
 *
 * <p>
 * 行を積むのはリクエストのスレッド、送るのは @Scheduled の別スレッドで、しかも後の時刻になる。
 * スレッドローカルでは渡せないため、traceparent を行に保存して復元している。
 * ここが切れると、1注文分のログが2本のトレースに割れる。
 */
@SpringBootTest
@Transactional
class DispatchTraceContinuityTest {

    @Autowired
    PspDispatchQueuePort pspDispatchQueuePort;

    @Autowired
    DispatchTraceContext traceContext;

    @Autowired
    PspDispatchStore pspDispatchStore;

    @Autowired
    Tracer tracer;

    @Autowired
    JdbcClient jdbcClient;

    private final PaymentId paymentId = PaymentId.generate();

    @BeforeEach
    void clear() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
    }

    private String storedTraceparent() {
        return jdbcClient.sql("SELECT traceparent FROM payment_psp_dispatch_events WHERE payment_id = :id")
                .param("id", paymentId.paymentId())
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("REQ-NFR-008: 行を積むとき、そのときのトレースが行に記録される")
    void enqueueStoresTheCurrentTraceContext() {
        Span span = tracer.nextSpan().name("caller").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            pspDispatchQueuePort.enqueue(PaymentOperation.AUTHORIZE, paymentId, new Money(1000, Currency.JPY));

            String traceparent = storedTraceparent();
            assertNotNull(traceparent, "traceparent が記録されていない");
            // W3C Trace Context version 00: 00-<32桁>-<16桁>-<2桁>
            assertEquals(55, traceparent.length());
            // 行に載った値が、そのときのトレースを指している。
            assertEquals(span.context().traceId(), traceparent.split("-")[1]);
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("REQ-NFR-008: 別スレッドで復元しても同じトレースの続きになる")
    void relayContinuesTheSameTrace() throws Exception {
        Span caller = tracer.nextSpan().name("caller").start();
        String callerTraceId;
        String traceparent;
        try (Tracer.SpanInScope scope = tracer.withSpan(caller)) {
            callerTraceId = caller.context().traceId();
            pspDispatchQueuePort.enqueue(PaymentOperation.AUTHORIZE, paymentId, new Money(1000, Currency.JPY));
            traceparent = storedTraceparent();
        } finally {
            caller.end();
        }

        // Relay は別スレッドで走る。スレッドローカルが引き継がれないことを明示するため、
        // 実際に別スレッドで復元する。
        String[] restored = new String[2];
        Thread relayThread = new Thread(() -> {
            Span span = traceContext.startSpan(traceparent, "psp-dispatch");
            try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                restored[0] = span.context().traceId();
                restored[1] = span.context().spanId();
            } finally {
                span.end();
            }
        });
        relayThread.start();
        relayThread.join();

        assertEquals(callerTraceId, restored[0], "同じトレースの続きになっていない");
        assertNotEquals(caller.context().spanId(), restored[1], "別の span として記録されるべき");
    }

    @Test
    @DisplayName("トレースが無い状態で積まれた行でも、送信時に新しいトレースを開始できる")
    void rowWithoutTraceparentStartsANewTrace() {
        Span span = traceContext.startSpan(null, "psp-dispatch");
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            assertNotNull(span.context().traceId());
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("トレースが有効でないときは traceparent を記録しない")
    void enqueueWithoutActiveTraceStoresNull() {
        // 呼び出し元に span が無い状態。バッチ処理など、リクエスト起点でない経路を想定する。
        pspDispatchQueuePort.enqueue(PaymentOperation.AUTHORIZE, paymentId, new Money(1000, Currency.JPY));

        long nullRows = jdbcClient
                .sql("SELECT count(*) FROM payment_psp_dispatch_events WHERE traceparent IS NULL")
                .query(Long.class)
                .single();
        assertEquals(1L, nullRows);
    }

    @Test
    @DisplayName("UUIDではなくW3C Trace Contextの形式で記録される")
    void traceparentFollowsTheW3cFormat() {
        Span span = tracer.nextSpan().name("caller").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            pspDispatchQueuePort.enqueue(PaymentOperation.REFUND, paymentId, new Money(1000, Currency.JPY));

            String[] parts = storedTraceparent().split("-");
            assertEquals(4, parts.length);
            assertEquals("00", parts[0], "version");
            assertEquals(32, parts[1].length(), "trace-id は16バイト");
            assertEquals(16, parts[2].length(), "span-id は8バイト");
        } finally {
            span.end();
        }
    }

    /**
     * 確保のSQLが traceparent を返していないと、Relay は復元する材料を得られない。
     * RETURNING 句への列の追加漏れは、ここでしか検出できない。
     */
    @Test
    @DisplayName("REQ-NFR-008: 送信対象を確保したとき、行に載せた traceparent も取り出される")
    void claimReturnsTheStoredTraceparent() {
        Span span = tracer.nextSpan().name("caller").start();
        String expected;
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            pspDispatchQueuePort.enqueue(PaymentOperation.AUTHORIZE, paymentId, new Money(1000, Currency.JPY));
            expected = storedTraceparent();
        } finally {
            span.end();
        }

        List<PspDispatchEventEntity> claimed = pspDispatchStore.claim(10, Duration.ofMinutes(1));

        assertEquals(1, claimed.size());
        assertEquals(expected, claimed.get(0).traceparent());
    }
}

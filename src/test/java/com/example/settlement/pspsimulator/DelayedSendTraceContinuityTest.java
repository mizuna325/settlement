package com.example.settlement.pspsimulator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.adapter.out.gateway.PspClient;
import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * 遅延送信を跨いでトレースが繋がることを確認する(design.md §8.1 の境界③)。
 *
 * <p>
 * シミュレータは受け付けから1〜5秒後、TaskScheduler の別スレッドで Webhook を送る。
 * スレッドローカルのコンテキストは越えないため、予約時に捕まえて Runnable に被せている。
 * ここが切れると、Webhook 以降の処理が元の注文と無関係な新しいトレースになる。
 *
 * <p>
 * 検証はログのMDCで行う。受信側(HandlePspWebhookService)のログに載る traceId が、
 * 発端のリクエストと同じであれば繋がっている。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "settlement.pspsimulator.webhook-delay-min=0s",
        "settlement.pspsimulator.webhook-delay-max=1s",
        "settlement.psp.webhook-secret=test-secret",
        "settlement.pspsimulator.webhook-secret=test-secret" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DelayedSendTraceContinuityTest {

    /** 適用結果を知るサービス。ここのログに載る traceId を見る。 */
    private static final String APPLYING_SERVICE = "com.example.settlement.payment.application.service.HandlePspWebhookService";

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("settlement.psp.base-url", () -> "http://localhost:" + PORT);
        registry.add("settlement.pspsimulator.webhook-url",
                () -> "http://localhost:" + PORT + "/payment/webhook");
    }

    @Autowired
    PspClient pspClient;

    @Autowired
    AuthorizePaymentUseCase authorizePaymentUseCase;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    Tracer tracer;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM payment_webhook_events").update();
        jdbcClient.sql("DELETE FROM payment_psp_idempotency_keys").update();
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
        jdbcClient.sql("DELETE FROM payment_refunds").update();
        jdbcClient.sql("DELETE FROM payment_captures").update();
        jdbcClient.sql("DELETE FROM payment_authorizations").update();
        jdbcClient.sql("DELETE FROM payments").update();
        jdbcClient.sql("DELETE FROM order_lines").update();
        jdbcClient.sql("DELETE FROM orders").update();
    }

    private PaymentId authorizingPayment() {
        Money amount = new Money(1000, Currency.JPY);
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), amount)));
        UUID orderId = orderRepository.save(order).getOrderId().orderId();
        return authorizePaymentUseCase.authorize(
                new com.example.settlement.payment.domain.OrderId(orderId), amount);
    }

    @Test
    @DisplayName("REQ-NFR-008: 遅延して送られた Webhook も、発端のリクエストと同じトレースになる")
    void delayedWebhookKeepsTheOriginalTrace() {
        PaymentId paymentId = authorizingPayment();

        ConcurrentLinkedQueue<String> receiverTraceIds = new ConcurrentLinkedQueue<>();
        ListAppender<ILoggingEvent> logs = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                super.append(event);
                receiverTraceIds.add(event.getMDCPropertyMap().get("traceId"));
            }
        };
        logs.start();
        ch.qos.logback.classic.Logger receiver = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(APPLYING_SERVICE);
        receiver.addAppender(logs);

        String callerTraceId;
        Span caller = tracer.nextSpan().name("caller").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(caller)) {
            callerTraceId = caller.context().traceId();
            // 受信側が WARN を出す状況を作る。与信を依頼していない決済へ通知が届く形にすると
            // NOT_APPLICABLE になり、HandlePspWebhookService がログを出す。
            pspClient.authorize(UUID.randomUUID(), UUID.randomUUID(), 1000, "JPY");
        } finally {
            caller.end();
        }

        try {
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> !receiverTraceIds.isEmpty());
        } finally {
            receiver.detachAppender(logs);
        }

        String received = receiverTraceIds.peek();
        assertNotNull(received, "受信側のログに traceId が載っていない");
        assertEquals(callerTraceId, received,
                "遅延送信でトレースが切れている。予約時にコンテキストを捕まえられていない");
        assertTrue(paymentId != null);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

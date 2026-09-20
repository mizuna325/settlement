package com.example.settlement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * ステップ3の完了条件(plan.md)。注文の受付から結果の確定までを通しで確認する。
 *
 * <p>
 * 個々の分岐は各クラスのテストで見ている。ここで確かめるのは、それらが1本に繋がること。
 * POST /orders から先は人手を介さず、Outbox → Relay → PSPシミュレータ →
 * 遅延Webhook → 署名検証 → 集約の更新、と流れて注文が確定する。
 *
 * <p>
 * ここだけは Relay を実際に走らせる。人手を介さないことが確認したい性質そのものなので、
 * 走査を手で呼ぶと確認にならない。ただし走査はテーブル全体を対象にするため、
 * このクラスが終わった後もスレッドが生き残ると他のテストの行を拾ってしまう。
 * {@code @DirtiesContext} でコンテキストごと閉じ、スケジューラを止める。
 *
 * <p>
 * テストに @Transactional を張らない。Relay もWebhookの送信も別スレッドの
 * 別トランザクションで走るため、テストのトランザクションに巻き込めない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "settlement.psp.dispatch.enabled=true",
        "settlement.psp.dispatch.polling-interval=200ms",
        "settlement.pspsimulator.webhook-delay-min=0s",
        "settlement.pspsimulator.webhook-delay-max=1s",
        "settlement.psp.webhook-secret=test-secret",
        "settlement.pspsimulator.webhook-secret=test-secret",
        // 走査とWebhookの送信が既定の1本を奪い合わないようにする。
        "spring.task.scheduling.pool.size=4" })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthorizationCycleTest {

    private static final String SECRET = "test-secret";

    /** 適用できなかった理由を知るのはサービス側なので、WARN もそちらに出る。 */
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
    MockMvc mockMvc;

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

    /** 注文を1件作る。金額はそのまま決済の金額になり、シミュレータの可否判定に使われる。 */
    private UUID placeOrder(long amount) throws Exception {
        UUID customerId = UUID.randomUUID();
        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "customerId": "%s",
                          "lines": [
                            { "productId": "SKU-1", "quantity": 1, "amount": %d, "currency": "JPY" }
                          ]
                        }
                        """.formatted(customerId, amount)))
                .andExpect(status().isCreated());

        return jdbcClient.sql("SELECT order_id FROM orders WHERE customer_id = :id")
                .param("id", customerId)
                .query(UUID.class)
                .single();
    }

    private String orderStatusOf(UUID orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private String paymentStatusOf(UUID orderId) {
        return jdbcClient.sql("SELECT status FROM payments WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private UUID paymentIdOf(UUID orderId) {
        return jdbcClient.sql("SELECT payment_id FROM payments WHERE order_id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .single();
    }

    private long webhookEventCount() {
        return jdbcClient.sql("SELECT count(*) FROM payment_webhook_events").query(Long.class).single();
    }

    private void awaitOrderStatus(UUID orderId, String expected) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> expected.equals(orderStatusOf(orderId)));
    }

    /**
     * Relay に拾わせたくないテストで使う。与信要求のディスパッチを取り除くことで、
     * 注文をその場に留めたまま Webhook の受け口だけを試せる。
     */
    private void cancelPendingDispatches() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
    }

    @Test
    @DisplayName("REQ-ORD-002/004: 与信成功から売上確定まで注文が SETTLED へ自動で進む")
    void authorizedOrderReachesSettled() throws Exception {
        UUID orderId = placeOrder(1000);

        // ここから先は人手を介さない。Relay の走査、PSPへの送信、遅延Webhook、
        // 署名検証、集約の更新がすべて自動で進む。与信成功はそのまま売上確定へ続く
        // (REQ-PAY-004)ため、CONFIRMED は通過点でしかない。
        awaitOrderStatus(orderId, "SETTLED");

        assertEquals("CAPTURED", paymentStatusOf(orderId));
        // 与信と売上確定でWebhookは2通届く。
        assertEquals(2L, webhookEventCount());
    }

    /** REQ-ORD-009: 返金を要求する。受け付けは 202 で、成立はWebhook到達後。 */
    private void requestRefund(UUID orderId, long amount) throws Exception {
        mockMvc.perform(post("/orders/" + orderId + "/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": %d, "currency": "JPY", "reason": "顧客都合"}
                        """.formatted(amount)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("REQ-ORD-006: 一部返金で注文が PARTIALLY_REFUNDED まで自動で進む")
    void partialRefundReachesPartiallyRefunded() throws Exception {
        UUID orderId = placeOrder(1000);
        awaitOrderStatus(orderId, "SETTLED");

        requestRefund(orderId, 300);

        awaitOrderStatus(orderId, "PARTIALLY_REFUNDED");
        assertEquals("PARTIALLY_REFUNDED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-ORD-006: 部分返金を積み上げて全額に達すると REFUNDED になる")
    void refundsAccumulateUntilFullyRefunded() throws Exception {
        UUID orderId = placeOrder(1000);
        awaitOrderStatus(orderId, "SETTLED");

        requestRefund(orderId, 400);
        awaitOrderStatus(orderId, "PARTIALLY_REFUNDED");

        requestRefund(orderId, 600);
        awaitOrderStatus(orderId, "REFUNDED");
        assertEquals("REFUNDED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PAY-008: 売上確定額を超える返金要求は受け付けられない")
    void refundExceedingTheCapturedAmountIsRejected() throws Exception {
        UUID orderId = placeOrder(1000);
        awaitOrderStatus(orderId, "SETTLED");

        // 集約が弾くため 409。GlobalExceptionHandler が IllegalStateException を変換する。
        mockMvc.perform(post("/orders/" + orderId + "/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 1001, "currency": "JPY", "reason": "顧客都合"}
                        """))
                .andExpect(status().isConflict());

        assertEquals("SETTLED", orderStatusOf(orderId));
        assertEquals("CAPTURED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PAY-007: 売上確定が終わっていない注文は返金できない")
    void refundBeforeSettlementIsRejected() throws Exception {
        UUID orderId = placeOrder(1099); // 与信が拒否される金額
        awaitOrderStatus(orderId, "CANCELLED");

        mockMvc.perform(post("/orders/" + orderId + "/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 100, "currency": "JPY", "reason": "顧客都合"}
                        """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("REQ-ORD-005: 売上確定失敗で注文が SETTLEMENT_FAILED になる")
    void captureFailureLeavesTheOrderForManualHandling() throws Exception {
        // REQ-SIM-004: 下2桁が 98 ならシミュレータが売上確定の失敗を返す。
        UUID orderId = placeOrder(1098);

        awaitOrderStatus(orderId, "SETTLEMENT_FAILED");
        assertEquals("CAPTURE_FAILED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-ORD-003: 与信拒否で注文が CANCELLED まで自動で進む")
    void declinedOrderReachesCancelled() throws Exception {
        // REQ-SIM-003: 下2桁が 99 ならシミュレータが拒否を返す。
        UUID orderId = placeOrder(1099);

        awaitOrderStatus(orderId, "CANCELLED");
        assertEquals("AUTH_DECLINED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-006: 同じ通知が再送されても二重に処理されない")
    void redeliveredWebhookIsNotAppliedTwice() throws Exception {
        UUID orderId = placeOrder(1000);
        awaitOrderStatus(orderId, "SETTLED");

        // REQ-SIM-007: シミュレータの手動再送で、同じ eventId の通知をもう一度届かせる。
        // 保持しているのは決済ごとに最新の1件なので、ここでは売上確定の通知が再送される。
        HttpStatusCode resendStatus = RestClient.create()
                .post()
                .uri("http://localhost:" + PORT + "/psp/webhooks/resend/" + paymentIdOf(orderId))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();

        assertEquals(HttpStatus.OK, resendStatus);
        // 与信と売上確定の2通のまま。再送は eventId が同じなので記録が増えない。
        assertEquals(2L, webhookEventCount());
        assertEquals("SETTLED", orderStatusOf(orderId));
        assertEquals("CAPTURED", paymentStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-005: 署名が不正な通知は 401 で弾かれ、注文は進まない")
    void webhookWithInvalidSignatureIsRejected() throws Exception {
        UUID orderId = placeOrder(1000);
        cancelPendingDispatches();

        String body = """
                {"eventId":"evt-%s","paymentId":"%s","pspReference":"psp-1","status":"AUTHORIZED"}"""
                .formatted(UUID.randomUUID(), paymentIdOf(orderId));

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(body, Instant.now(), "wrong-secret"))
                .content(body))
                .andExpect(status().isUnauthorized());

        assertEquals("PENDING", orderStatusOf(orderId));
        assertEquals("AUTHORIZING", paymentStatusOf(orderId));
        assertEquals(0L, webhookEventCount());
    }

    @Test
    @DisplayName("REQ-PSP-007: 適用できない通知は 200 を返し、WARN を残す")
    void webhookThatCannotBeAppliedIsAcknowledged() throws Exception {
        UUID orderId = placeOrder(1099);
        awaitOrderStatus(orderId, "CANCELLED");

        // 拒否で確定した後に、到達が遅れた与信成功が届く状況。
        String body = """
                {"eventId":"evt-%s","paymentId":"%s","pspReference":"psp-1","status":"AUTHORIZED"}"""
                .formatted(UUID.randomUUID(), paymentIdOf(orderId));

        ListAppender<ILoggingEvent> logs = captureLogsOf(APPLYING_SERVICE);
        try {
            mockMvc.perform(post("/payment/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Psp-Signature", signatureHeader(body, Instant.now(), SECRET))
                    .content(body))
                    .andExpect(status().isOk());
        } finally {
            detach(APPLYING_SERVICE, logs);
        }

        assertEquals("CANCELLED", orderStatusOf(orderId));
        assertEquals("AUTH_DECLINED", paymentStatusOf(orderId));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN),
                "適用できなかったことを知らせるWARNログが出ていない");
    }

    private static String signatureHeader(String body, Instant signedAt, String secret) {
        long epochSecond = signedAt.getEpochSecond();
        return "t=%d,v1=%s".formatted(epochSecond, hmacHex(epochSecond + "." + body, secret));
    }

    private static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ListAppender<ILoggingEvent> captureLogsOf(String loggerName) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).addAppender(appender);
        return appender;
    }

    private static void detach(String loggerName, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).detachAppender(appender);
        appender.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

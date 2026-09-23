package com.example.settlement;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** PSPへ送った事実を残すのは境界側なので、INFO はこちらに出る。 */
    private static final String DISPATCH_RELAY = "com.example.settlement.payment.adapter.out.outbox.PspDispatchRelay";

    /** order 側の起点。POST /orders のスレッドで出る。 */
    private static final String ORDER_CREATE = "com.example.settlement.order.application.service.CreateOrderService";

    /** order 側の終点。Webhook が届いたスレッドで出る。 */
    private static final String ORDER_SETTLE = "com.example.settlement.order.application.service.SettleOrderService";

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

    /**
     * REQ-NFR-008: 1注文分の処理が1本のトレースとして追える。
     *
     * <p>
     * 注文の受付から確定までに、処理は4回スレッドをまたぐ(design.md §8.1)。
     * うち2つはHTTPなので自動で繋がるが、Outbox の行を経由する箇所と
     * TaskScheduler の遅延実行は、IDをデータとして持ち回らないと切れる。
     *
     * <p>
     * 検証には Outbox の行に記録された traceparent を使う。与信の行は POST /orders の
     * スレッドで、売上確定の行は Webhook が届いた後のスレッドで積まれる。その間に
     * 4つの境界すべてを通るため、両者の traceId が一致していれば全体が繋がっている。
     * ログを見るより確実で、タイミングにも依存しない。
     *
     * <p>
     * 個々の境界は DispatchTraceContinuityTest と
     * DelayedSendTraceContinuityTest がそれぞれ見ている。
     */
    @Test
    @DisplayName("REQ-NFR-008: 注文の受付から確定までが1本のトレースとして繋がる")
    void theWholeCycleStaysInOneTrace() throws Exception {
        UUID orderId = placeOrder(1000);
        awaitOrderStatus(orderId, "SETTLED");

        // 与信のディスパッチは POST /orders のスレッドで積まれる。
        // 売上確定のディスパッチは、Webhook が届いた後のスレッドで積まれる。
        // その間に4つの境界すべてを通るので、両者の traceId が一致していれば全体が繋がっている。
        List<String> traceparents = jdbcClient.sql("""
                SELECT d.traceparent
                  FROM payment_psp_dispatch_events d
                  JOIN payments p ON p.payment_id = d.payment_id
                 WHERE p.order_id = :id
                 ORDER BY d.created_at
                """)
                .param("id", orderId)
                .query(String.class)
                .list();

        assertEquals(2, traceparents.size(), "与信と売上確定で2件のディスパッチが積まれる");
        traceparents.forEach(t -> assertNotNull(t, "traceparent が記録されていない"));

        String authorizeTraceId = traceparents.get(0).split("-")[1];
        String captureTraceId = traceparents.get(1).split("-")[1];
        assertEquals(authorizeTraceId, captureTraceId,
                "注文の受付から売上確定の依頼まででトレースが分断されている");

        // span は境界ごとに新しくなる。同じ span のまま運ばれているわけではない。
        assertNotEquals(traceparents.get(0).split("-")[2], traceparents.get(1).split("-")[2]);
    }

    /**
     * REQ-NFR-008 の「全ログ」にあたる部分。トレースが繋がっているだけでなく、
     * 正常系にも実際にログが出ていて、それが1本に載っていることを見る。
     *
     * <p>
     * {@link #theWholeCycleStaysInOneTrace} は Outbox の列を見ており、ログ出力には
     * 触れていない。データ上で繋がっていてもログが1行も出なければ運用では追えないため、
     * ここを別に確かめる。
     *
     * <p>
     * あわせて出力の形も見る。業務IDがメッセージ本文への埋め込みではなく、
     * MDC と key-value のフィールドとして出ていること(design.md §8.8)。
     * ここが崩れると構造化ログとして検索できなくなるが、動作は変わらないため
     * テストで押さえないと気付けない。
     */
    @Test
    @DisplayName("REQ-NFR-008: 正常系のログが1本のトレースに載り、業務IDがフィールドとして出る")
    void theSuccessfulPathEmitsCorrelatedLogs() throws Exception {
        ListAppender<ILoggingEvent> relayLogs = captureLogsOf(DISPATCH_RELAY);
        ListAppender<ILoggingEvent> serviceLogs = captureLogsOf(APPLYING_SERVICE);
        ListAppender<ILoggingEvent> createLogs = captureLogsOf(ORDER_CREATE);
        ListAppender<ILoggingEvent> settleLogs = captureLogsOf(ORDER_SETTLE);

        UUID orderId;
        try {
            orderId = placeOrder(1000);
            awaitOrderStatus(orderId, "SETTLED");
        } finally {
            detach(DISPATCH_RELAY, relayLogs);
            detach(APPLYING_SERVICE, serviceLogs);
            detach(ORDER_CREATE, createLogs);
            detach(ORDER_SETTLE, settleLogs);
        }

        UUID paymentId = paymentIdOf(orderId);
        // Relay はテーブル全体を走査するため、他のテストが残した行を拾いうる。
        // MDCの値で絞る。この絞り込み自体が、IDがMDCに載っていることの確認にもなっている。
        List<ILoggingEvent> sent = eventsFor(relayLogs, "paymentId", paymentId);
        List<ILoggingEvent> applied = eventsFor(serviceLogs, "paymentId", paymentId);
        List<ILoggingEvent> created = eventsFor(createLogs, "orderId", orderId);
        List<ILoggingEvent> settled = eventsFor(settleLogs, "orderId", orderId);

        assertEquals(2, sent.size(), "与信と売上確定でPSPへ2回送っているはず");
        assertEquals(2, applied.size(), "与信結果と売上確定結果で2回適用しているはず");
        assertEquals(1, created.size(), "注文の受付が記録されていない");
        assertEquals(1, settled.size(), "注文の完了が記録されていない");

        // order と payment は別コンテキストで、間に4つの非同期境界がある。
        // それでも全行が同じ traceId を持つ。ここが分かれると、将来サービスを分割したときに
        // 片方のログだけを見ても全体が追えない状態になる。
        Set<String> traceIds = Stream.of(sent, applied, created, settled)
                .flatMap(List::stream)
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .collect(Collectors.toSet());
        assertEquals(1, traceIds.size(), "正常系のログが複数のトレースに分かれている: " + traceIds);
        assertNotNull(traceIds.iterator().next(), "ログに traceId が載っていない");

        ILoggingEvent applyLog = applied.get(0);
        // 本文は固定。値が混ざると同じ事象を数えられなくなる。
        assertEquals("PSPの通知を決済へ適用した", applyLog.getMessage());
        assertEquals(orderId.toString(), keyValueOf(applyLog, "orderId"));
        assertNotNull(applyLog.getMDCPropertyMap().get("eventId"), "eventId がMDCに載っていない");

        ILoggingEvent sendLog = sent.get(0);
        assertEquals("PSPへ送信し、受理された", sendLog.getMessage());
        assertEquals("AUTHORIZE", keyValueOf(sendLog, "operation"));
        assertNotNull(sendLog.getMDCPropertyMap().get("dispatchEventId"),
                "dispatchEventId がMDCに載っていない");

        // order 側の終端。状態はフィールドで出るため、本文に埋め込まれていない。
        ILoggingEvent settleLog = settled.get(0);
        assertEquals("売上が確定したため注文を完了した", settleLog.getMessage());
        assertEquals("SETTLED", keyValueOf(settleLog, "orderStatus"));
        // order の遷移は決済の通知から駆動される。payment 側のIDも同じ行に載っている。
        assertEquals(paymentId.toString(), settleLog.getMDCPropertyMap().get("paymentId"));
    }

    /** MDCの指定キーが一致するログだけを取り出す。 */
    private static List<ILoggingEvent> eventsFor(ListAppender<ILoggingEvent> appender, String key, UUID value) {
        return appender.list.stream()
                .filter(event -> value.toString().equals(event.getMDCPropertyMap().get(key)))
                .toList();
    }

    /** SLF4J の fluent API で付けた値を読む。MDC とは別の入れ物に入る。 */
    private static String keyValueOf(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value))
                .findFirst()
                .orElse(null);
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

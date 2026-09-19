package com.example.settlement.payment.adapter.in.webhook;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * POST /payment/webhook の結合テスト(REQ-PSP-005, REQ-PSP-007)。
 *
 * <p>
 * 署名は共有シークレットによる HMAC-SHA256 で、`X-Psp-Signature: t=<epoch>,v1=<hex>` の形式。
 * 署名対象は `t + "." + リクエストボディ` とする(ステップ3の決定事項)。時刻を署名に含めることで、
 * 盗まれた署名の再利用を許容時間の経過で無効化する。
 *
 * <p>
 * シークレットはテストで固定する。環境変数に依存すると結果が変わるため。
 */
@SpringBootTest(properties = "settlement.psp.webhook-secret=test-secret")
@AutoConfigureMockMvc
@Transactional
class PspWebhookControllerTest {

    private static final String SECRET = "test-secret";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AuthorizePaymentUseCase authorizePaymentUseCase;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcClient jdbcClient;

    private final Money amount = new Money(1000, Currency.JPY);

    private UUID pendingOrder() {
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), amount)));
        return orderRepository.save(order).getOrderId().orderId();
    }

    private PaymentId authorizingPayment(UUID orderId) {
        return authorizePaymentUseCase.authorize(
                new com.example.settlement.payment.domain.OrderId(orderId), amount);
    }

    private static String body(PaymentId paymentId, String status, String pspReference) {
        return """
                {"eventId":"evt-%s","paymentId":"%s","status":"%s","pspReference":%s}
                """.formatted(
                UUID.randomUUID(),
                paymentId.paymentId(),
                status,
                pspReference == null ? "null" : "\"" + pspReference + "\"");
    }

    /** PSP側が付ける署名ヘッダーを組み立てる。シミュレータが実装する処理と同じもの。 */
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

    private String paymentStatusOf(PaymentId paymentId) {
        return jdbcClient.sql("SELECT status FROM payments WHERE payment_id = :id")
                .param("id", paymentId.paymentId())
                .query(String.class)
                .single();
    }

    private String orderStatusOf(UUID orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("REQ-PSP-005: 正しい署名の与信成功で 200 を返し、Order が CONFIRMED になる")
    void validSignatureAppliesTheOutcome() throws Exception {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String body = body(paymentId, "AUTHORIZED", "psp-ref-1");

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(body, Instant.now(), SECRET))
                .content(body))
                .andExpect(status().isOk());

        assertEquals("AUTHORIZED", paymentStatusOf(paymentId));
        assertEquals("CONFIRMED", orderStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-005: 署名が不正なら 401 を返し、いかなる状態変更も行わない")
    void invalidSignatureIsRejected() throws Exception {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String body = body(paymentId, "AUTHORIZED", "psp-ref-1");

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(body, Instant.now(), "wrong-secret"))
                .content(body))
                .andExpect(status().isUnauthorized());

        assertEquals("AUTHORIZING", paymentStatusOf(paymentId));
        assertEquals("PENDING", orderStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-005: 署名ヘッダーが無ければ 401 を返す")
    void missingSignatureIsRejected() throws Exception {
        PaymentId paymentId = authorizingPayment(pendingOrder());

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(paymentId, "AUTHORIZED", "psp-ref-1")))
                .andExpect(status().isUnauthorized());

        assertEquals("AUTHORIZING", paymentStatusOf(paymentId));
    }

    @Test
    @DisplayName("REQ-PSP-005: 署名対象と異なるボディで送られたら 401 を返す")
    void tamperedBodyIsRejected() throws Exception {
        PaymentId paymentId = authorizingPayment(pendingOrder());
        String signedBody = body(paymentId, "AUTHORIZED", "psp-ref-1");
        String tamperedBody = body(paymentId, "DECLINED", null);

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(signedBody, Instant.now(), SECRET))
                .content(tamperedBody))
                .andExpect(status().isUnauthorized());

        assertEquals("AUTHORIZING", paymentStatusOf(paymentId));
    }

    @Test
    @DisplayName("REQ-PSP-005: 許容時間を過ぎた署名は再利用できず 401 になる")
    void staleSignatureIsRejected() throws Exception {
        PaymentId paymentId = authorizingPayment(pendingOrder());
        String body = body(paymentId, "AUTHORIZED", "psp-ref-1");
        Instant longAgo = Instant.now().minus(Duration.ofHours(1));

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(body, longAgo, SECRET))
                .content(body))
                .andExpect(status().isUnauthorized());

        assertEquals("AUTHORIZING", paymentStatusOf(paymentId));
    }

    @Test
    @DisplayName("REQ-PSP-006: 同じボディの再送は 200 を返し、状態は二重に進まない")
    void redeliveryIsAcknowledged() throws Exception {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String body = body(paymentId, "AUTHORIZED", "psp-ref-1");
        String signature = signatureHeader(body, Instant.now(), SECRET);

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signature)
                .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signature)
                .content(body))
                .andExpect(status().isOk());

        assertEquals("AUTHORIZED", paymentStatusOf(paymentId));
        assertEquals("CONFIRMED", orderStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-007: 適用できない通知は 200 を返し、WARN を残す")
    void notApplicableWebhookIsAcknowledgedWithWarning() throws Exception {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);

        String declineBody = body(paymentId, "DECLINED", null);
        mockMvc.perform(post("/payment/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Psp-Signature", signatureHeader(declineBody, Instant.now(), SECRET))
                .content(declineBody))
                .andExpect(status().isOk());

        // 拒否で確定した後に、到達が遅れた与信成功が届く状況。
        String lateBody = body(paymentId, "AUTHORIZED", "psp-ref-1");
        ListAppender<ILoggingEvent> logs = captureLogsOf(PspWebhookController.class);
        try {
            mockMvc.perform(post("/payment/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Psp-Signature", signatureHeader(lateBody, Instant.now(), SECRET))
                    .content(lateBody))
                    .andExpect(status().isOk());
        } finally {
            detach(PspWebhookController.class, logs);
        }

        assertEquals("AUTH_DECLINED", paymentStatusOf(paymentId));
        assertEquals("CANCELLED", orderStatusOf(orderId));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.WARN),
                "適用できなかったことを知らせるWARNログが出ていない");
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
}

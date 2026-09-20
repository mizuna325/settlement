package com.example.settlement.pspsimulator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
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

/**
 * シミュレータが与信結果をWebhookで通知するところまで(REQ-SIM-002, REQ-SIM-003, REQ-SIM-006)。
 *
 * <p>
 * 署名の生成が受信側の検証と噛み合うかを、実際にHTTPで往復させて確かめる。
 * 一致しなければ受信側が401を返し、注文の状態が変わらないまま止まる。
 *
 * <p>
 * テストに @Transactional を張らない。送信は別スレッドの別トランザクションで走るため、
 * テストのトランザクションに巻き込めない。代わりに前後で明示的に消す。
 *
 * <p>
 * 遅延は 1秒固定にしている。0にすると「同期的に送っていない」ことを確かめられず、
 * 既定の 1〜5秒のままだとテストが無駄に長くなる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "settlement.pspsimulator.webhook-delay-min=1s",
        "settlement.pspsimulator.webhook-delay-max=1s",
        "settlement.psp.webhook-secret=test-secret",
        "settlement.pspsimulator.webhook-secret=test-secret" })
class WebhookDispatcherTest {

    /** 固定の8080だと開発中のアプリと衝突するため、空きポートを取ってから起動する。 */
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
    JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM payment_webhook_events").update();
        jdbcClient.sql("DELETE FROM payment_psp_idempotency_keys").update();
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
        jdbcClient.sql("DELETE FROM payment_authorizations").update();
        jdbcClient.sql("DELETE FROM payments").update();
        jdbcClient.sql("DELETE FROM order_lines").update();
        jdbcClient.sql("DELETE FROM orders").update();
    }

    /** 注文と、与信要求まで済ませた決済を用意する。 */
    private PaymentId authorizingPayment(UUID orderId, long amount) {
        return authorizePaymentUseCase.authorize(
                new com.example.settlement.payment.domain.OrderId(orderId),
                new Money(amount, Currency.JPY));
    }

    private UUID pendingOrder(long amount) {
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), new Money(amount, Currency.JPY))));
        return orderRepository.save(order).getOrderId().orderId();
    }

    private String orderStatusOf(UUID orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private String paymentStatusOf(PaymentId paymentId) {
        return jdbcClient.sql("SELECT status FROM payments WHERE payment_id = :id")
                .param("id", paymentId.paymentId())
                .query(String.class)
                .single();
    }

    private long webhookEventCount() {
        return jdbcClient.sql("SELECT count(*) FROM payment_webhook_events")
                .query(Long.class)
                .single();
    }

    private void awaitPaymentStatus(PaymentId paymentId, String expected) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> expected.equals(paymentStatusOf(paymentId)));
    }

    @Test
    @DisplayName("REQ-SIM-006: シミュレータの署名は受信側の検証を通り、与信成功が反映される")
    void authorizedResultReachesTheReceiver() {
        UUID orderId = pendingOrder(1000);
        PaymentId paymentId = authorizingPayment(orderId, 1000);

        pspClient.authorize(UUID.randomUUID(), paymentId.paymentId(), 1000, "JPY");

        awaitPaymentStatus(paymentId, "AUTHORIZED");
        assertEquals("CONFIRMED", orderStatusOf(orderId));
        assertEquals(1L, webhookEventCount());
    }

    @Test
    @DisplayName("REQ-SIM-003: 金額の下2桁が 99 なら拒否として通知される")
    void amountEndingIn99IsDeclined() {
        UUID orderId = pendingOrder(1099);
        PaymentId paymentId = authorizingPayment(orderId, 1099);

        pspClient.authorize(UUID.randomUUID(), paymentId.paymentId(), 1099, "JPY");

        awaitPaymentStatus(paymentId, "AUTH_DECLINED");
        assertEquals("CANCELLED", orderStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-SIM-003: 下2桁が 99 でなければ承認される")
    void otherAmountsAreAuthorized() {
        UUID orderId = pendingOrder(9900);
        PaymentId paymentId = authorizingPayment(orderId, 9900);

        pspClient.authorize(UUID.randomUUID(), paymentId.paymentId(), 9900, "JPY");

        awaitPaymentStatus(paymentId, "AUTHORIZED");
    }

    @Test
    @DisplayName("REQ-SIM-002: 202 を返した時点では結果を送っていない")
    void resultIsNotSentSynchronously() {
        UUID orderId = pendingOrder(1000);
        PaymentId paymentId = authorizingPayment(orderId, 1000);

        pspClient.authorize(UUID.randomUUID(), paymentId.paymentId(), 1000, "JPY");

        // 202 が返った直後。遅延1秒を設定しているので、まだ届いていない。
        assertEquals("AUTHORIZING", paymentStatusOf(paymentId));
        assertEquals("PENDING", orderStatusOf(orderId));

        awaitPaymentStatus(paymentId, "AUTHORIZED");
    }

    @Test
    @DisplayName("REQ-SIM-005: 受付済みの冪等性キーでは結果を二重に通知しない")
    void duplicateRequestDoesNotSendTwice() {
        UUID orderId = pendingOrder(1000);
        PaymentId paymentId = authorizingPayment(orderId, 1000);
        UUID idempotencyKey = UUID.randomUUID();

        pspClient.authorize(idempotencyKey, paymentId.paymentId(), 1000, "JPY");
        pspClient.authorize(idempotencyKey, paymentId.paymentId(), 1000, "JPY");

        awaitPaymentStatus(paymentId, "AUTHORIZED");

        // 2通目が送られていれば eventId が別なので2行目が残る。
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .until(() -> webhookEventCount() == 1L);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

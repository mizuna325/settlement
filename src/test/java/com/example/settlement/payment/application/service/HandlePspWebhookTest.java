package com.example.settlement.payment.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;
import com.example.settlement.payment.application.port.in.HandlePspWebhookUseCase;
import com.example.settlement.payment.application.port.in.PspWebhookNotification;
import com.example.settlement.payment.application.port.in.PspWebhookStatus;
import com.example.settlement.payment.application.port.in.WebhookOutcome;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * REQ-PSP-008: 冪等チェック・集約更新・PaymentOutcomePort の呼び出しが
 * 単一のトランザクションで完結することを、実DBと実際の結線で確認する。
 *
 * <p>
 * Webhook の入口(HTTP・署名検証)は含まない。そこは PspWebhookController の担当で、
 * 端から端までの検証はステップ3の結合テストで行う。
 */
@SpringBootTest
@Transactional
class HandlePspWebhookTest {

    @Autowired
    HandlePspWebhookUseCase handlePspWebhookUseCase;

    @Autowired
    AuthorizePaymentUseCase authorizePaymentUseCase;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcClient jdbcClient;

    private final Money amount = new Money(1000, Currency.JPY);

    /** 注文と、それに対する与信要求済みの決済を用意する。 */
    private PaymentId authorizingPayment(UUID orderId) {
        return authorizePaymentUseCase.authorize(
                new com.example.settlement.payment.domain.OrderId(orderId), amount);
    }

    private UUID pendingOrder() {
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), amount)));
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

    private long webhookEventCountOf(String eventId) {
        return jdbcClient.sql("SELECT count(*) FROM payment_webhook_events WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("REQ-PSP-008: 与信成功で Order が CONFIRMED、受信記録が残る")
    void authorizedWebhookUpdatesBothAggregates() {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String eventId = "evt-" + UUID.randomUUID();

        WebhookOutcome outcome = handlePspWebhookUseCase.handle(
                new PspWebhookNotification(eventId, paymentId, PspWebhookStatus.AUTHORIZED, "psp-ref-1"));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals("CONFIRMED", orderStatusOf(orderId));
        assertEquals(1L, webhookEventCountOf(eventId));
        // REQ-PAY-004: 与信成功は同一トランザクション内でそのまま売上確定へ進むため、
        // AUTHORIZED は永続化された状態としては観測されない(requirements.md §2.2)。
        assertEquals("CAPTURING", paymentStatusOf(paymentId));
    }

    @Test
    @DisplayName("REQ-PSP-008: 与信拒否で Payment が AUTH_DECLINED、Order が CANCELLED になる")
    void declinedWebhookUpdatesBothAggregates() {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String eventId = "evt-" + UUID.randomUUID();

        WebhookOutcome outcome = handlePspWebhookUseCase.handle(
                new PspWebhookNotification(eventId, paymentId, PspWebhookStatus.DECLINED, null));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals("AUTH_DECLINED", paymentStatusOf(paymentId));
        assertEquals("CANCELLED", orderStatusOf(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-006: 同じ eventId の再送では状態が二重に進まない")
    void redeliveryDoesNotApplyTwice() {
        UUID orderId = pendingOrder();
        PaymentId paymentId = authorizingPayment(orderId);
        String eventId = "evt-" + UUID.randomUUID();
        PspWebhookNotification notification = new PspWebhookNotification(
                eventId, paymentId, PspWebhookStatus.AUTHORIZED, "psp-ref-1");

        handlePspWebhookUseCase.handle(notification);
        WebhookOutcome redelivery = handlePspWebhookUseCase.handle(notification);

        assertEquals(WebhookOutcome.DUPLICATE, redelivery);
        assertEquals("CAPTURING", paymentStatusOf(paymentId));
        assertEquals("CONFIRMED", orderStatusOf(orderId));
        assertEquals(1L, webhookEventCountOf(eventId));
    }
}

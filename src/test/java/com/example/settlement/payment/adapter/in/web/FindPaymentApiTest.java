package com.example.settlement.payment.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * REQ-PAY-013: 決済の照会。
 *
 * <p>
 * 見たいのは金額の内訳が状態に追随することで、そこが注文側の照会(REQ-ORD-007)との
 * 違いにあたる。注文は「どうなったか」だけを返すが、こちらは
 * 「与信は通ったが売上確定で落ちた」「いくら戻っている」に答える。
 *
 * <p>
 * 集約を直接組み立てて保存する。PSPを経由させるとWebhookの到達を待つことになり、
 * ここで確かめたい金額の対応関係がタイミングに埋もれる。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FindPaymentApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
        jdbcClient.sql("DELETE FROM payment_refunds").update();
        jdbcClient.sql("DELETE FROM payment_captures").update();
        jdbcClient.sql("DELETE FROM payment_authorizations").update();
        jdbcClient.sql("DELETE FROM payments").update();
        jdbcClient.sql("DELETE FROM order_lines").update();
        jdbcClient.sql("DELETE FROM orders").update();
    }

    private static final Money AMOUNT = new Money(1000, Currency.JPY);

    /** 与信を依頼しただけの決済を作る。まだ何も確定していない。 */
    private Payment authorizingPayment() {
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), AMOUNT)));
        UUID orderId = orderRepository.save(order).getOrderId().orderId();
        Payment payment = Payment.create(
                new com.example.settlement.payment.domain.OrderId(orderId), AMOUNT);
        return paymentRepository.save(payment);
    }

    @Test
    @DisplayName("REQ-PAY-013: 結果待ちの間は確定した金額が無いので0が並ぶ")
    void nothingIsSettledWhileAwaitingTheResult() throws Exception {
        PaymentId paymentId = authorizingPayment().getPaymentId();

        mockMvc.perform(get("/payments/" + paymentId.paymentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZING"))
                // 依頼額は分かっているが、まだ何も押さえられていない。
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.authorizedAmount").value(0))
                .andExpect(jsonPath("$.capturedAmount").value(0))
                .andExpect(jsonPath("$.refundedTotal").value(0))
                .andExpect(jsonPath("$.currency").value("JPY"));
    }

    @Test
    @DisplayName("REQ-PAY-013: 与信が拒否された決済は与信額も0のまま")
    void declinedAuthorizationLeavesNothingHeld() throws Exception {
        Payment payment = authorizingPayment();
        payment.declineAuthorization();
        paymentRepository.save(payment);

        mockMvc.perform(get("/payments/" + payment.getPaymentId().paymentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTH_DECLINED"))
                // 依頼はしたが成立していない。依頼額を与信額として見せない。
                .andExpect(jsonPath("$.authorizedAmount").value(0));
    }

    @Test
    @DisplayName("REQ-PAY-013: 部分返金の後は3つの金額が並んで見える")
    void partialRefundShowsAllThreeAmounts() throws Exception {
        Instant now = Instant.now();
        Payment payment = authorizingPayment();
        payment.recordAuthorization("psp-auth", now, Duration.ofDays(7));
        payment.capture(AMOUNT, now);
        payment.recordCapture("psp-capture", now);
        payment.requestRefund(new Money(300, Currency.JPY), new RefundReason("顧客都合"), now);
        payment.confirmRefund("psp-refund");
        paymentRepository.save(payment);

        mockMvc.perform(get("/payments/" + payment.getPaymentId().paymentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.authorizedAmount").value(1000))
                .andExpect(jsonPath("$.capturedAmount").value(1000))
                // 売上確定額は返金しても減らない。戻した額は別に持つ。
                .andExpect(jsonPath("$.refundedTotal").value(300));
    }

    @Test
    @DisplayName("REQ-PAY-013: 処理中の返金は累計に含めない")
    void pendingRefundIsNotCountedYet() throws Exception {
        Instant now = Instant.now();
        Payment payment = authorizingPayment();
        payment.recordAuthorization("psp-auth", now, Duration.ofDays(7));
        payment.capture(AMOUNT, now);
        payment.recordCapture("psp-capture", now);
        payment.requestRefund(new Money(300, Currency.JPY), new RefundReason("顧客都合"), now);
        paymentRepository.save(payment);

        mockMvc.perform(get("/payments/" + payment.getPaymentId().paymentId()))
                .andExpect(status().isOk())
                // PSPの結果が返るまでは戻っていない。ここで数えると二重返金の判断を誤らせる。
                .andExpect(jsonPath("$.refundedTotal").value(0));
    }

    @Test
    @DisplayName("存在しない決済IDは 404 を返す")
    void unknownPaymentIsNotFound() throws Exception {
        mockMvc.perform(get("/payments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}

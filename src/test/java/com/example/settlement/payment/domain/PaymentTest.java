package com.example.settlement.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

class PaymentTest {

    private static OrderId orderId() {
        return new OrderId(UUID.randomUUID());
    }

    private static Money jpy(long amount) {
        return new Money(amount, Currency.JPY);
    }

    @Test
    @DisplayName("REQ-PAY-001: 与信要求で Payment は AUTHORIZING で生成される")
    void createdPaymentIsAuthorizing() {
        Payment payment = Payment.create(orderId(), jpy(1000));

        assertEquals(PaymentStatus.AUTHORIZING, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("REQ-PAY-001: 与信要求で Authorization が PENDING で生成される")
    void createdPaymentHasPendingAuthorization() {
        Payment payment = Payment.create(orderId(), jpy(1000));

        assertNotNull(payment.getAuthorization());
        assertEquals(AuthorizationStatus.PENDING, payment.getAuthorization().getAuthorizationStatus());
    }

    @Test
    @DisplayName("REQ-PAY-001: Authorization の金額は Payment の金額と一致する")
    void authorizationAmountMatchesPaymentAmount() {
        Payment payment = Payment.create(orderId(), jpy(1000));

        assertEquals(jpy(1000), payment.getAmount());
        assertEquals(jpy(1000), payment.getAuthorization().getAmount());
    }

    @Test
    @DisplayName("REQ-PAY-001: 生成直後はPSP側の参照IDと日時が未確定である")
    void createdAuthorizationHasNoPspResultYet() {
        Authorization authorization = Payment.create(orderId(), jpy(1000)).getAuthorization();

        assertNull(authorization.getPspReference());
        assertNull(authorization.getAuthorizedAt());
        assertNull(authorization.getExpiresAt());
    }

    @Test
    @DisplayName("新規生成した決済のバージョンは0である")
    void createdPaymentHasVersionZero() {
        Payment payment = Payment.create(orderId(), jpy(1000));

        assertEquals(0, payment.getVersion());
    }

    @Test
    @DisplayName("注文IDのない決済は生成できない")
    void paymentWithoutOrderIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Payment.create(null, jpy(1000)));
    }

    @Test
    @DisplayName("金額のない決済は生成できない")
    void paymentWithoutAmountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Payment.create(orderId(), null));
    }

    @Test
    @DisplayName("復元時に必須項目が欠けている場合は拒否される")
    void reconstructRejectsMissingRequiredValues() {
        PaymentId paymentId = PaymentId.generate();
        OrderId orderId = orderId();
        Money amount = jpy(1000);
        Authorization authorization = Authorization.create(AuthorizationId.generate(), amount);

        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, null, orderId, amount, PaymentStatus.AUTHORIZING, authorization));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, null, amount, PaymentStatus.AUTHORIZING, authorization));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, null, PaymentStatus.AUTHORIZING, authorization));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, amount, null, authorization));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, amount, PaymentStatus.AUTHORIZING, null));
    }

    @Test
    @DisplayName("復元した決済は渡した値をそのまま保持する")
    void reconstructRestoresAllValues() {
        PaymentId paymentId = PaymentId.generate();
        OrderId orderId = orderId();
        Instant authorizedAt = Instant.parse("2026-08-11T00:00:00Z");
        Instant expiresAt = authorizedAt.plusSeconds(604800);
        Authorization authorization = Authorization.reconstruct(AuthorizationId.generate(), jpy(1000), "psp-ref-1",
                AuthorizationStatus.AUTHORIZED, authorizedAt, expiresAt);

        Payment payment = Payment.reconstruct(3, paymentId, orderId, jpy(1000), PaymentStatus.AUTHORIZED,
                authorization);

        assertEquals(3, payment.getVersion());
        assertEquals(paymentId, payment.getPaymentId());
        assertEquals(orderId, payment.getOrderId());
        assertEquals(jpy(1000), payment.getAmount());
        assertEquals(PaymentStatus.AUTHORIZED, payment.getPaymentStatus());
        assertEquals("psp-ref-1", payment.getAuthorization().getPspReference());
        assertEquals(expiresAt, payment.getAuthorization().getExpiresAt());
    }
}

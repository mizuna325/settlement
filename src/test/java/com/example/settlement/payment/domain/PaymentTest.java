package com.example.settlement.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

class PaymentTest {

    private static final Instant AUTHORIZED_AT = Instant.parse("2026-09-02T00:00:00Z");

    /** REQ-NFR-005: 与信の有効期限は7日。設定値なのでドメインの外から渡す。 */
    private static final Duration VALIDITY = Duration.ofDays(7);

    private static OrderId orderId() {
        return new OrderId(UUID.randomUUID());
    }

    private static Money jpy(long amount) {
        return new Money(amount, Currency.JPY);
    }

    private static Payment authorizing() {
        return Payment.create(orderId(), jpy(1000));
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

    @Test
    @DisplayName("REQ-PAY-002: 与信成功で Payment と Authorization が AUTHORIZED になる")
    void recordAuthorizationMovesBothToAuthorized() {
        Payment payment = authorizing();

        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);

        assertEquals(PaymentStatus.AUTHORIZED, payment.getPaymentStatus());
        assertEquals(AuthorizationStatus.AUTHORIZED, payment.getAuthorization().getAuthorizationStatus());
    }

    @Test
    @DisplayName("REQ-PAY-002: 与信成功でPSP側の参照IDと与信日時が記録される")
    void recordAuthorizationStoresPspResult() {
        Payment payment = authorizing();

        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);

        assertEquals("psp-ref-1", payment.getAuthorization().getPspReference());
        assertEquals(AUTHORIZED_AT, payment.getAuthorization().getAuthorizedAt());
    }

    @Test
    @DisplayName("REQ-NFR-005: 有効期限は与信日時に有効期間を加えた時刻になる")
    void expiresAtIsAuthorizedAtPlusValidity() {
        Payment payment = authorizing();

        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);

        assertEquals(AUTHORIZED_AT.plus(VALIDITY), payment.getAuthorization().getExpiresAt());
    }

    @Test
    @DisplayName("REQ-PAY-003: 与信拒否で Authorization が DECLINED、Payment が AUTH_DECLINED になる")
    void declineAuthorizationMovesBothToDeclined() {
        Payment payment = authorizing();

        payment.declineAuthorization();

        assertEquals(PaymentStatus.AUTH_DECLINED, payment.getPaymentStatus());
        assertEquals(AuthorizationStatus.DECLINED, payment.getAuthorization().getAuthorizationStatus());
    }

    @Test
    @DisplayName("REQ-PAY-003: 与信拒否では与信日時と有効期限を設定しない")
    void declineAuthorizationLeavesTimestampsUnset() {
        Payment payment = authorizing();

        payment.declineAuthorization();

        assertNull(payment.getAuthorization().getAuthorizedAt());
        assertNull(payment.getAuthorization().getExpiresAt());
    }

    @Test
    @DisplayName("確定済みの与信に同じ結果を再度適用することはできない")
    void authorizationIsNotAppliedTwice() {
        Payment payment = authorizing();
        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);

        assertThrows(IllegalStateException.class,
                () -> payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY));
    }

    @Test
    @DisplayName("拒否済みの与信を成功に覆すことはできない")
    void declinedAuthorizationCannotBeAuthorized() {
        Payment payment = authorizing();
        payment.declineAuthorization();

        assertThrows(IllegalStateException.class,
                () -> payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY));
    }

    @Test
    @DisplayName("成功済みの与信を拒否に覆すことはできない")
    void authorizedAuthorizationCannotBeDeclined() {
        Payment payment = authorizing();
        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);

        assertThrows(IllegalStateException.class, () -> payment.declineAuthorization());
    }

    @Test
    @DisplayName("PSP側の参照IDのない与信成功は記録できない")
    void authorizationWithoutPspReferenceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization(null, AUTHORIZED_AT, VALIDITY));
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization(" ", AUTHORIZED_AT, VALIDITY));
    }

    @Test
    @DisplayName("与信日時のない与信成功は記録できない")
    void authorizationWithoutAuthorizedAtIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization("psp-ref-1", null, VALIDITY));
    }

    @Test
    @DisplayName("有効期間が正でない与信成功は記録できない")
    void authorizationWithNonPositiveValidityIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization("psp-ref-1", AUTHORIZED_AT, null));
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization("psp-ref-1", AUTHORIZED_AT, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> authorizing().recordAuthorization("psp-ref-1", AUTHORIZED_AT, Duration.ofDays(-1)));
    }
}

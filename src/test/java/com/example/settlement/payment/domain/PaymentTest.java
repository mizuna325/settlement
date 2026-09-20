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
                () -> Payment.reconstruct(1, null, orderId, amount, PaymentStatus.AUTHORIZING, authorization, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, null, amount, PaymentStatus.AUTHORIZING, authorization, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, null, PaymentStatus.AUTHORIZING, authorization, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, amount, null, authorization, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> Payment.reconstruct(1, paymentId, orderId, amount, PaymentStatus.AUTHORIZING, null, null, null));
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
                authorization, null, null);

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

    // ---- 売上確定(ステップ4) ----

    /** 与信が成功し、売上確定を依頼できる状態。 */
    private static Payment authorized() {
        Payment payment = authorizing();
        payment.recordAuthorization("psp-ref-1", AUTHORIZED_AT, VALIDITY);
        return payment;
    }

    /** 有効期限内の時刻。与信日時の翌日。 */
    private static final Instant WITHIN_VALIDITY = AUTHORIZED_AT.plus(Duration.ofDays(1));

    @Test
    @DisplayName("REQ-PAY-004: 売上確定の依頼で Payment が CAPTURING、Capture が PENDING になる")
    void captureStartsAsPending() {
        Payment payment = authorized();

        payment.capture(jpy(1000), WITHIN_VALIDITY);

        assertEquals(PaymentStatus.CAPTURING, payment.getPaymentStatus());
        assertNotNull(payment.getCapture());
        assertEquals(CaptureStatus.PENDING, payment.getCapture().getCaptureStatus());
        assertEquals(jpy(1000), payment.getCapture().getAmount());
    }

    @Test
    @DisplayName("REQ-PAY-004: 依頼直後はPSP側の参照IDと確定日時が未確定である")
    void createdCaptureHasNoPspResultYet() {
        Payment payment = authorized();

        payment.capture(jpy(1000), WITHIN_VALIDITY);

        assertNull(payment.getCapture().getPspReference());
        assertNull(payment.getCapture().getCapturedAt());
    }

    @Test
    @DisplayName("REQ-PAY-005: 与信額を超える売上確定は拒否される")
    void captureExceedingAuthorizedAmountIsRejected() {
        Payment payment = authorized();

        assertThrows(IllegalStateException.class, () -> payment.capture(jpy(1001), WITHIN_VALIDITY));

        // 拒否された場合は集約の状態を変えない。
        assertEquals(PaymentStatus.AUTHORIZED, payment.getPaymentStatus());
        assertNull(payment.getCapture());
    }

    @Test
    @DisplayName("REQ-PAY-005: 与信額と同額の売上確定は許可される")
    void captureOfTheFullAuthorizedAmountIsAllowed() {
        Payment payment = authorized();

        payment.capture(jpy(1000), WITHIN_VALIDITY);

        assertEquals(PaymentStatus.CAPTURING, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("REQ-PAY-006: 与信の有効期限を過ぎた売上確定は拒否される")
    void captureAfterAuthorizationExpiredIsRejected() {
        Payment payment = authorized();
        Instant afterExpiry = AUTHORIZED_AT.plus(VALIDITY).plusSeconds(1);

        assertThrows(IllegalStateException.class, () -> payment.capture(jpy(1000), afterExpiry));

        assertEquals(PaymentStatus.AUTHORIZED, payment.getPaymentStatus());
        assertNull(payment.getCapture());
    }

    @Test
    @DisplayName("REQ-PAY-010: PENDING の売上確定がある状態で追加の依頼はできない")
    void captureWhileAnotherIsPendingIsRejected() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);

        assertThrows(IllegalStateException.class, () -> payment.capture(jpy(1000), WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("与信が成立していない決済は売上確定を依頼できない")
    void captureWithoutAuthorizationIsRejected() {
        Payment payment = authorizing();

        assertThrows(IllegalStateException.class, () -> payment.capture(jpy(1000), WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("REQ-ORD-004: 売上確定成功で Payment と Capture が CAPTURED になる")
    void recordCaptureMovesBothToCaptured() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);

        payment.recordCapture("psp-cap-1", WITHIN_VALIDITY);

        assertEquals(PaymentStatus.CAPTURED, payment.getPaymentStatus());
        assertEquals(CaptureStatus.CAPTURED, payment.getCapture().getCaptureStatus());
        assertEquals("psp-cap-1", payment.getCapture().getPspReference());
        assertEquals(WITHIN_VALIDITY, payment.getCapture().getCapturedAt());
    }

    @Test
    @DisplayName("REQ-ORD-005: 売上確定失敗で Payment が CAPTURE_FAILED、Capture が FAILED になる")
    void failCaptureMovesBothToFailed() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);

        payment.failCapture();

        assertEquals(PaymentStatus.CAPTURE_FAILED, payment.getPaymentStatus());
        assertEquals(CaptureStatus.FAILED, payment.getCapture().getCaptureStatus());
    }

    @Test
    @DisplayName("依頼していない売上確定の結果は記録できない")
    void captureResultWithoutRequestIsRejected() {
        Payment payment = authorized();

        assertThrows(IllegalStateException.class, () -> payment.recordCapture("psp-cap-1", WITHIN_VALIDITY));
        assertThrows(IllegalStateException.class, () -> payment.failCapture());
    }

    @Test
    @DisplayName("確定済みの売上確定に同じ結果を再度適用することはできない")
    void captureIsNotAppliedTwice() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);
        payment.recordCapture("psp-cap-1", WITHIN_VALIDITY);

        assertThrows(IllegalStateException.class, () -> payment.recordCapture("psp-cap-1", WITHIN_VALIDITY));
        assertThrows(IllegalStateException.class, () -> payment.failCapture());
    }

    @Test
    @DisplayName("PSP側の参照IDのない売上確定成功は記録できない")
    void captureWithoutPspReferenceIsRejected() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);

        assertThrows(IllegalArgumentException.class, () -> payment.recordCapture(null, WITHIN_VALIDITY));
        assertThrows(IllegalArgumentException.class, () -> payment.recordCapture(" ", WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("売上確定の依頼に必須の値が欠けている場合は拒否される")
    void captureRequestRejectsMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> authorized().capture(null, WITHIN_VALIDITY));
        assertThrows(IllegalArgumentException.class, () -> authorized().capture(jpy(1000), null));
    }

    // ---- 返金(ステップ5) ----

    private static final RefundReason REASON = new RefundReason("顧客都合");

    /** 売上確定まで完了し、返金を受け付けられる状態。 */
    private static Payment captured() {
        Payment payment = authorized();
        payment.capture(jpy(1000), WITHIN_VALIDITY);
        payment.recordCapture("psp-cap-1", WITHIN_VALIDITY);
        return payment;
    }

    @Test
    @DisplayName("REQ-ORD-009: 返金要求で Payment が REFUNDING、Refund が PENDING で追加される")
    void refundRequestStartsAsPending() {
        Payment payment = captured();

        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        assertEquals(PaymentStatus.REFUNDING, payment.getPaymentStatus());
        assertEquals(1, payment.getRefunds().size());
        Refund refund = payment.getRefunds().get(0);
        assertEquals(RefundStatus.PENDING, refund.getRefundStatus());
        assertEquals(jpy(300), refund.getAmount());
        assertEquals(REASON, refund.getReason());
        assertNull(refund.getPspReference());
    }

    @Test
    @DisplayName("REQ-PAY-007: 売上確定が完了していない決済は返金できない")
    void refundBeforeCaptureIsRejected() {
        Payment authorizedOnly = authorized();
        assertThrows(IllegalStateException.class,
                () -> authorizedOnly.requestRefund(jpy(100), REASON, WITHIN_VALIDITY));

        Payment capturing = authorized();
        capturing.capture(jpy(1000), WITHIN_VALIDITY);
        assertThrows(IllegalStateException.class,
                () -> capturing.requestRefund(jpy(100), REASON, WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("REQ-PAY-008: 売上確定額を超える返金は拒否される")
    void refundExceedingCapturedAmountIsRejected() {
        Payment payment = captured();

        assertThrows(IllegalStateException.class,
                () -> payment.requestRefund(jpy(1001), REASON, WITHIN_VALIDITY));

        assertEquals(PaymentStatus.CAPTURED, payment.getPaymentStatus());
        assertEquals(0, payment.getRefunds().size());
    }

    @Test
    @DisplayName("REQ-PAY-008: 部分返金を積み上げて売上確定額を超える要求は拒否される")
    void refundTotalExceedingCapturedAmountIsRejected() {
        Payment payment = captured();
        payment.requestRefund(jpy(600), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r1");

        assertThrows(IllegalStateException.class,
                () -> payment.requestRefund(jpy(401), REASON, WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("REQ-PAY-008: 処理中の返金も累計に含めて超過を判定する")
    void pendingRefundCountsTowardTheTotal() {
        Payment payment = captured();
        payment.requestRefund(jpy(600), REASON, WITHIN_VALIDITY);
        payment.failRefund();
        payment.requestRefund(jpy(600), REASON, WITHIN_VALIDITY);

        // 失敗した600は累計に含まれないが、処理中の600は含まれる。
        assertThrows(IllegalStateException.class,
                () -> payment.requestRefund(jpy(401), REASON, WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("REQ-PAY-010: PENDING の返金がある状態で追加の要求はできない")
    void refundWhileAnotherIsPendingIsRejected() {
        Payment payment = captured();
        payment.requestRefund(jpy(100), REASON, WITHIN_VALIDITY);

        assertThrows(IllegalStateException.class,
                () -> payment.requestRefund(jpy(100), REASON, WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("REQ-PAY-009: 一部を返金した時点では PARTIALLY_REFUNDED になる")
    void partialRefundLeavesPaymentPartiallyRefunded() {
        Payment payment = captured();
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        payment.confirmRefund("psp-ref-r1");

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getPaymentStatus());
        assertEquals(RefundStatus.REFUNDED, payment.getRefunds().get(0).getRefundStatus());
        assertEquals("psp-ref-r1", payment.getRefunds().get(0).getPspReference());
    }

    @Test
    @DisplayName("REQ-PAY-009: 累計が売上確定額に達したら REFUNDED になる")
    void refundReachingTheCapturedAmountCompletesTheRefund() {
        Payment payment = captured();
        payment.requestRefund(jpy(400), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r1");

        payment.requestRefund(jpy(600), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r2");

        assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());
        assertEquals(2, payment.getRefunds().size());
    }

    @Test
    @DisplayName("REQ-PAY-009: 一度の全額返金でも REFUNDED になる")
    void fullRefundInOneRequestCompletesTheRefund() {
        Payment payment = captured();
        payment.requestRefund(jpy(1000), REASON, WITHIN_VALIDITY);

        payment.confirmRefund("psp-ref-r1");

        assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("REQ-PAY-011: 返金失敗で Refund が FAILED、Payment が CAPTURED へ戻る")
    void failedRefundRestoresCaptured() {
        Payment payment = captured();
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        payment.failRefund();

        assertEquals(PaymentStatus.CAPTURED, payment.getPaymentStatus());
        assertEquals(RefundStatus.FAILED, payment.getRefunds().get(0).getRefundStatus());
    }

    @Test
    @DisplayName("REQ-PAY-011: 既に一部返金済みなら失敗時は PARTIALLY_REFUNDED へ戻る")
    void failedRefundRestoresPartiallyRefunded() {
        Payment payment = captured();
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r1");
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        payment.failRefund();

        // 直前の状態は列に持たず、確定済みの累計(300 > 0)から導出する。
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("REQ-PAY-011: 失敗した返金は累計に数えない")
    void failedRefundIsNotCountedInTheTotal() {
        Payment payment = captured();
        payment.requestRefund(jpy(1000), REASON, WITHIN_VALIDITY);
        payment.failRefund();

        // 失敗分が累計に残っていれば、この全額返金が超過として弾かれる。
        payment.requestRefund(jpy(1000), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r2");

        assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());
    }

    @Test
    @DisplayName("処理中の返金がなければ結果は適用できない")
    void refundResultWithoutPendingRefundIsRejected() {
        Payment payment = captured();

        assertThrows(IllegalStateException.class, () -> payment.confirmRefund("psp-ref-r1"));
        assertThrows(IllegalStateException.class, () -> payment.failRefund());
    }

    @Test
    @DisplayName("全額返金後は追加の返金を要求できない")
    void refundAfterFullRefundIsRejected() {
        Payment payment = captured();
        payment.requestRefund(jpy(1000), REASON, WITHIN_VALIDITY);
        payment.confirmRefund("psp-ref-r1");

        assertThrows(IllegalStateException.class,
                () -> payment.requestRefund(jpy(1), REASON, WITHIN_VALIDITY));
    }

    @Test
    @DisplayName("PSP側の参照IDのない返金完了は記録できない")
    void refundWithoutPspReferenceIsRejected() {
        Payment payment = captured();
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        assertThrows(IllegalArgumentException.class, () -> payment.confirmRefund(null));
        assertThrows(IllegalArgumentException.class, () -> payment.confirmRefund(" "));
    }

    @Test
    @DisplayName("返金の要求に必須の値が欠けている場合は拒否される")
    void refundRequestRejectsMissingValues() {
        assertThrows(IllegalArgumentException.class, () -> captured().requestRefund(null, REASON, WITHIN_VALIDITY));
        assertThrows(IllegalArgumentException.class, () -> captured().requestRefund(jpy(100), null, WITHIN_VALIDITY));
        assertThrows(IllegalArgumentException.class, () -> captured().requestRefund(jpy(100), REASON, null));
    }

    @Test
    @DisplayName("返金の一覧は外から変更できない")
    void refundsAreNotModifiableFromOutside() {
        Payment payment = captured();
        payment.requestRefund(jpy(300), REASON, WITHIN_VALIDITY);

        assertThrows(UnsupportedOperationException.class,
                () -> payment.getRefunds().add(Refund.create(RefundId.generate(), jpy(1), REASON, WITHIN_VALIDITY)));
    }
}

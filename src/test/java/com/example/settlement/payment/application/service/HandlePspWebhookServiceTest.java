package com.example.settlement.payment.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.payment.application.AuthorizationProperties;
import com.example.settlement.payment.application.port.in.PspWebhookNotification;
import com.example.settlement.payment.application.port.in.PspWebhookStatus;
import com.example.settlement.payment.application.port.in.WebhookOutcome;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentCaptureFailed;
import com.example.settlement.payment.application.port.out.PaymentCaptured;
import com.example.settlement.payment.application.port.out.PaymentRefunded;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.WebhookEventStorePort;
import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentStatus;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * Webhook受信の分岐を、協力者をモックにして確認する。
 * 実DBと結線した経路は {@link HandlePspWebhookTest} で見る。
 */
class HandlePspWebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    /** REQ-NFR-005: 与信の有効期限は7日。 */
    private static final Duration VALIDITY = Duration.ofDays(7);

    private final WebhookEventStorePort webhookEventStorePort = mock(WebhookEventStorePort.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentOutcomePort paymentOutcomePort = mock(PaymentOutcomePort.class);

    /**
     * 与信成功に続く売上確定はモックにする。ここで見たいのは「同一トランザクション内で
     * 依頼されること」であって、依頼そのものの中身は CapturePaymentService の担当。
     */
    private final CapturePaymentService capturePaymentService = mock(CapturePaymentService.class);

    private final HandlePspWebhookService service = new HandlePspWebhookService(
            webhookEventStorePort,
            paymentRepository,
            paymentOutcomePort,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new AuthorizationProperties(VALIDITY),
            capturePaymentService);

    private final OrderId orderId = new OrderId(UUID.randomUUID());
    private final PaymentId paymentId = PaymentId.generate();

    /** 与信要求を出した直後の状態(AUTHORIZING)。 */
    private Payment authorizing() {
        Payment payment = Payment.create(orderId, new Money(1000, Currency.JPY));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return payment;
    }

    private void firstDelivery() {
        when(webhookEventStorePort.registerIfAbsent(any(), any())).thenReturn(true);
    }

    private static PspWebhookNotification authorized(PaymentId paymentId) {
        return new PspWebhookNotification("evt-1", paymentId, PspWebhookStatus.AUTHORIZED, "psp-ref-1");
    }

    private static PspWebhookNotification declined(PaymentId paymentId) {
        return new PspWebhookNotification("evt-1", paymentId, PspWebhookStatus.DECLINED, null);
    }

    private static PspWebhookNotification captured(PaymentId paymentId) {
        return new PspWebhookNotification("evt-2", paymentId, PspWebhookStatus.CAPTURED, "psp-cap-1");
    }

    private static PspWebhookNotification captureFailed(PaymentId paymentId) {
        return new PspWebhookNotification("evt-2", paymentId, PspWebhookStatus.CAPTURE_FAILED, null);
    }

    /** 与信が成立し、売上確定をPSPへ依頼済みの状態(CAPTURING)。 */
    private Payment capturing() {
        Payment payment = authorizing();
        payment.recordAuthorization("psp-ref-1", NOW, VALIDITY);
        payment.capture(payment.getAmount(), NOW);
        return payment;
    }

    @Test
    @DisplayName("REQ-PAY-002: 与信成功の通知で Payment が AUTHORIZED になり保存される")
    void authorizedWebhookIsApplied() {
        firstDelivery();
        Payment payment = authorizing();

        WebhookOutcome outcome = service.handle(authorized(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.AUTHORIZED, payment.getPaymentStatus());
        assertEquals("psp-ref-1", payment.getAuthorization().getPspReference());
        verify(paymentRepository).save(payment);
    }

    @Test
    @DisplayName("REQ-NFR-005: 与信日時は受信時刻、有効期限はそこに有効期間を加えた時刻になる")
    void authorizedAtComesFromTheClock() {
        firstDelivery();
        Payment payment = authorizing();

        service.handle(authorized(paymentId));

        assertEquals(NOW, payment.getAuthorization().getAuthorizedAt());
        assertEquals(NOW.plus(VALIDITY), payment.getAuthorization().getExpiresAt());
    }

    @Test
    @DisplayName("REQ-ORD-002: 与信成功は PaymentOutcomePort へ通知される")
    void authorizedWebhookNotifiesTheOutcomePort() {
        firstDelivery();
        authorizing();

        service.handle(authorized(paymentId));

        verify(paymentOutcomePort).authorized(new PaymentAuthorized(orderId));
        verify(paymentOutcomePort, never()).declined(any());
    }

    @Test
    @DisplayName("REQ-PAY-003: 与信拒否の通知で Payment が AUTH_DECLINED になり通知される")
    void declinedWebhookIsApplied() {
        firstDelivery();
        Payment payment = authorizing();

        WebhookOutcome outcome = service.handle(declined(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.AUTH_DECLINED, payment.getPaymentStatus());
        verify(paymentRepository).save(payment);
        verify(paymentOutcomePort).declined(new PaymentAuthDeclined(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-006: 処理済みの eventId は DUPLICATE を返し、何も触らない")
    void duplicateWebhookChangesNothing() {
        when(webhookEventStorePort.registerIfAbsent(any(), any())).thenReturn(false);

        WebhookOutcome outcome = service.handle(authorized(paymentId));

        assertEquals(WebhookOutcome.DUPLICATE, outcome);
        verifyNoInteractions(paymentRepository);
        verifyNoInteractions(paymentOutcomePort);
    }

    @Test
    @DisplayName("REQ-PSP-006: 受信の記録には受信時刻が渡される")
    void eventIsRecordedWithTheReceivedAt() {
        firstDelivery();
        authorizing();

        service.handle(authorized(paymentId));

        verify(webhookEventStorePort).registerIfAbsent("evt-1", NOW);
    }

    @Test
    @DisplayName("REQ-PSP-007: 現在の状態に適用できない通知は NOT_APPLICABLE を返し、保存も通知もしない")
    void notApplicableWebhookIsRejectedWithoutSideEffects() {
        firstDelivery();
        Payment payment = authorizing();
        payment.declineAuthorization();

        WebhookOutcome outcome = service.handle(authorized(paymentId));

        assertEquals(WebhookOutcome.NOT_APPLICABLE, outcome);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentOutcomePort);
    }

    @Test
    @DisplayName("REQ-PSP-007: 存在しない paymentId の通知も NOT_APPLICABLE として扱う")
    void webhookForUnknownPaymentIsNotApplicable() {
        firstDelivery();
        when(paymentRepository.findById(any())).thenReturn(Optional.empty());

        WebhookOutcome outcome = service.handle(authorized(PaymentId.generate()));

        assertEquals(WebhookOutcome.NOT_APPLICABLE, outcome);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentOutcomePort);
    }

    // ---- 売上確定(ステップ4) ----

    @Test
    @DisplayName("REQ-PAY-004: 与信成功と同一トランザクション内で売上確定を開始する")
    void authorizedWebhookStartsCaptureInTheSameTransaction() {
        firstDelivery();
        Payment payment = authorizing();

        service.handle(authorized(paymentId));

        verify(capturePaymentService).capture(payment, NOW);
    }

    @Test
    @DisplayName("REQ-PAY-004: 与信拒否では売上確定へ進まない")
    void declinedWebhookDoesNotStartCapture() {
        firstDelivery();
        authorizing();

        service.handle(declined(paymentId));

        verifyNoInteractions(capturePaymentService);
    }

    @Test
    @DisplayName("REQ-ORD-004: 売上確定成功の通知で Payment が CAPTURED になり通知される")
    void capturedWebhookIsApplied() {
        firstDelivery();
        Payment payment = capturing();

        WebhookOutcome outcome = service.handle(captured(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.CAPTURED, payment.getPaymentStatus());
        assertEquals("psp-cap-1", payment.getCapture().getPspReference());
        assertEquals(NOW, payment.getCapture().getCapturedAt());
        verify(paymentRepository).save(payment);
        verify(paymentOutcomePort).captured(new PaymentCaptured(orderId));
    }

    @Test
    @DisplayName("REQ-ORD-005: 売上確定失敗の通知で Payment が CAPTURE_FAILED になり通知される")
    void captureFailedWebhookIsApplied() {
        firstDelivery();
        Payment payment = capturing();

        WebhookOutcome outcome = service.handle(captureFailed(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.CAPTURE_FAILED, payment.getPaymentStatus());
        verify(paymentRepository).save(payment);
        verify(paymentOutcomePort).captureFailed(new PaymentCaptureFailed(orderId));
    }

    @Test
    @DisplayName("REQ-PSP-007: 売上確定を依頼していない決済への確定通知は NOT_APPLICABLE になる")
    void capturedWebhookWithoutRequestIsNotApplicable() {
        firstDelivery();
        authorizing();

        WebhookOutcome outcome = service.handle(captured(paymentId));

        assertEquals(WebhookOutcome.NOT_APPLICABLE, outcome);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentOutcomePort);
    }

    // ---- 返金(ステップ5) ----

    private static PspWebhookNotification refunded(PaymentId paymentId) {
        return new PspWebhookNotification("evt-3", paymentId, PspWebhookStatus.REFUNDED, "psp-ref-r1");
    }

    private static PspWebhookNotification refundFailed(PaymentId paymentId) {
        return new PspWebhookNotification("evt-3", paymentId, PspWebhookStatus.REFUND_FAILED, null);
    }

    /** 売上確定まで済み、返金をPSPへ依頼済みの状態(REFUNDING)。 */
    private Payment refunding(long refundAmount) {
        Payment payment = capturing();
        payment.recordCapture("psp-cap-1", NOW);
        payment.requestRefund(new Money(refundAmount, Currency.JPY), new RefundReason("顧客都合"), NOW);
        return payment;
    }

    @Test
    @DisplayName("REQ-ORD-006: 一部返金の完了では全額到達でないことを通知する")
    void partialRefundNotifiesAsNotFullyRefunded() {
        firstDelivery();
        Payment payment = refunding(300);

        WebhookOutcome outcome = service.handle(refunded(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getPaymentStatus());
        verify(paymentRepository).save(payment);
        verify(paymentOutcomePort).refunded(new PaymentRefunded(orderId, false));
    }

    @Test
    @DisplayName("REQ-ORD-006: 全額に達した返金では全額到達として通知する")
    void fullRefundNotifiesAsFullyRefunded() {
        firstDelivery();
        Payment payment = refunding(1000);

        service.handle(refunded(paymentId));

        assertEquals(PaymentStatus.REFUNDED, payment.getPaymentStatus());
        verify(paymentOutcomePort).refunded(new PaymentRefunded(orderId, true));
    }

    @Test
    @DisplayName("REQ-PAY-011: 返金失敗では直前の状態へ戻し、注文側へは通知しない")
    void failedRefundRestoresPreviousStateWithoutNotifying() {
        firstDelivery();
        Payment payment = refunding(300);

        WebhookOutcome outcome = service.handle(refundFailed(paymentId));

        assertEquals(WebhookOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.CAPTURED, payment.getPaymentStatus());
        verify(paymentRepository).save(payment);
        // 返金が成立していないので注文の状態は変えない。
        verifyNoInteractions(paymentOutcomePort);
    }

    @Test
    @DisplayName("REQ-PSP-007: 返金を依頼していない決済への返金通知は NOT_APPLICABLE になる")
    void refundedWebhookWithoutRequestIsNotApplicable() {
        firstDelivery();
        Payment payment = capturing();
        payment.recordCapture("psp-cap-1", NOW);

        WebhookOutcome outcome = service.handle(refunded(paymentId));

        assertEquals(WebhookOutcome.NOT_APPLICABLE, outcome);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(paymentOutcomePort);
    }
}

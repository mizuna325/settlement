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
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.WebhookEventStorePort;
import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentStatus;
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

    private final HandlePspWebhookService service = new HandlePspWebhookService(
            webhookEventStorePort,
            paymentRepository,
            paymentOutcomePort,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new AuthorizationProperties(VALIDITY));

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
}

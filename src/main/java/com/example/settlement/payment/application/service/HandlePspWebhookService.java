package com.example.settlement.payment.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.payment.application.AuthorizationProperties;
import com.example.settlement.payment.application.port.in.HandlePspWebhookUseCase;
import com.example.settlement.payment.application.port.in.PspWebhookNotification;
import com.example.settlement.payment.application.port.in.WebhookOutcome;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.WebhookEventStorePort;
import com.example.settlement.payment.domain.Payment;

@Service
class HandlePspWebhookService implements HandlePspWebhookUseCase {

    private final WebhookEventStorePort webhookEventStorePort;
    private final PaymentRepository paymentRepository;
    private final PaymentOutcomePort paymentOutcomePort;
    private final Clock clock;
    private final AuthorizationProperties authorizationProperties;

    HandlePspWebhookService(
            WebhookEventStorePort webhookEventStorePort,
            PaymentRepository paymentRepository,
            PaymentOutcomePort paymentOutcomePort,
            Clock clock,
            AuthorizationProperties authorizationProperties) {
        this.webhookEventStorePort = webhookEventStorePort;
        this.paymentRepository = paymentRepository;
        this.paymentOutcomePort = paymentOutcomePort;
        this.clock = clock;
        this.authorizationProperties = authorizationProperties;
    }

    @Transactional
    @Override
    public WebhookOutcome handle(PspWebhookNotification notification) {
        Instant receivedAt = this.clock.instant();
        if (!webhookEventStorePort.registerIfAbsent(notification.eventId(), receivedAt)) {
            return WebhookOutcome.DUPLICATE;
        }
        Optional<Payment> found = paymentRepository.findById(notification.paymentId());
        if (found.isEmpty()) {
            return WebhookOutcome.NOT_APPLICABLE;
        }
        Payment payment = found.get();

        try {
            // switch 文は列挙値の書き漏らしを検出しない。PspWebhookStatus に値を足すときは
            // ここの分岐も必ず追加すること(素通りすると APPLIED が返り、PSPは再送しない)。
            switch (notification.status()) {
                case AUTHORIZED -> {
                    payment.recordAuthorization(notification.pspReference(), receivedAt,
                            authorizationProperties.authorizationValidity());
                    paymentRepository.save(payment);
                    paymentOutcomePort.authorized(new PaymentAuthorized(payment.getOrderId()));
                }
                case DECLINED -> {
                    payment.declineAuthorization();
                    paymentRepository.save(payment);
                    paymentOutcomePort.declined(new PaymentAuthDeclined(payment.getOrderId()));
                }
            }
            return WebhookOutcome.APPLIED;
        } catch (IllegalStateException e) {
            // 到達順序は保証されないため、現在の状態に適用できない通知は異常ではない(REQ-PSP-007)。
            return WebhookOutcome.NOT_APPLICABLE;
        }
    }
}

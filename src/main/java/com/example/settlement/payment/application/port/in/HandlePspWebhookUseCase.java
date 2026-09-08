package com.example.settlement.payment.application.port.in;

public interface HandlePspWebhookUseCase {

    WebhookOutcome handle(PspWebhookNotification notification);
}

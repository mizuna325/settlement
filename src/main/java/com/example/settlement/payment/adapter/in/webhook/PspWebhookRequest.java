package com.example.settlement.payment.adapter.in.webhook;

import java.util.UUID;

import com.example.settlement.payment.application.port.in.PspWebhookStatus;

record PspWebhookRequest(String eventId, UUID paymentId, String pspReference,
        PspWebhookStatus status) {

}

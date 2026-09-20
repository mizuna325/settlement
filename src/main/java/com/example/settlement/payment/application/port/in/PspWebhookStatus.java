package com.example.settlement.payment.application.port.in;

/**
 * Webhookで通知される結果の種別。
 *
 * <p>
 * 値を追加したら {@code HandlePspWebhookService} の分岐も必ず追加すること。
 * switch 文は書き漏らしを検出しない。素通りすると APPLIED が返って eventId も記録されるため、
 * PSPは再送せず決済が宙に浮く。
 */
public enum PspWebhookStatus {
    AUTHORIZED,
    DECLINED,
    CAPTURED,
    CAPTURE_FAILED,
    REFUNDED,
    REFUND_FAILED
}

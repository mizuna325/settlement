package com.example.settlement.payment.application.port.in;

import com.example.settlement.payment.domain.PaymentId;

/**
 * PSPから届いた与信結果の通知。
 *
 * <p>
 * pspReference は検証しない。与信成功に参照IDが要ることは Payment 集約の不変条件であり、
 * 二重に持たない(design.md §3)。拒否時は記録しないため null で届く。
 */
public record PspWebhookNotification(String eventId, PaymentId paymentId, PspWebhookStatus status,
        String pspReference) {
    public PspWebhookNotification {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be null or blank.");
        }
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null.");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null.");
        }
    }

}

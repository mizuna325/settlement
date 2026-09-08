package com.example.settlement.payment.application.port.in;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.payment.domain.PaymentId;

class PspWebhookNotificationTest {

    private static final PaymentId PAYMENT_ID = PaymentId.generate();

    @Test
    void holdsTheGivenValues() {
        PspWebhookNotification notification = new PspWebhookNotification(
                "evt-1", PAYMENT_ID, PspWebhookStatus.AUTHORIZED, "psp-ref-1");

        assertEquals("evt-1", notification.eventId());
        assertEquals(PAYMENT_ID, notification.paymentId());
        assertEquals(PspWebhookStatus.AUTHORIZED, notification.status());
        assertEquals("psp-ref-1", notification.pspReference());
    }

    @Test
    @DisplayName("REQ-PSP-006: 冪等性の判定に使うため eventId は必須")
    void rejectsMissingEventId() {
        assertThrows(IllegalArgumentException.class,
                () -> new PspWebhookNotification(null, PAYMENT_ID, PspWebhookStatus.AUTHORIZED, "psp-ref-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new PspWebhookNotification(" ", PAYMENT_ID, PspWebhookStatus.AUTHORIZED, "psp-ref-1"));
    }

    @Test
    void rejectsMissingPaymentId() {
        assertThrows(IllegalArgumentException.class,
                () -> new PspWebhookNotification("evt-1", null, PspWebhookStatus.AUTHORIZED, "psp-ref-1"));
    }

    @Test
    void rejectsMissingStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new PspWebhookNotification("evt-1", PAYMENT_ID, null, "psp-ref-1"));
    }

    /** 拒否時は pspReference を記録しない(ステップ3の決定事項)ため、null で届くのが正常。 */
    @Test
    void acceptsDeclinedWithoutPspReference() {
        PspWebhookNotification notification = new PspWebhookNotification(
                "evt-1", PAYMENT_ID, PspWebhookStatus.DECLINED, null);

        assertEquals(PspWebhookStatus.DECLINED, notification.status());
    }
}

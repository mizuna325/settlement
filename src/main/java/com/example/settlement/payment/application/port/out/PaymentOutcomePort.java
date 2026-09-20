package com.example.settlement.payment.application.port.out;

public interface PaymentOutcomePort {
    void authorized(PaymentAuthorized event);

    void declined(PaymentAuthDeclined event);

    void captured(PaymentCaptured event);

    void captureFailed(PaymentCaptureFailed event);

    void refunded(PaymentRefunded event);

}

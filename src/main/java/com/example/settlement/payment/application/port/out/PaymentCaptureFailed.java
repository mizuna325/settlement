package com.example.settlement.payment.application.port.out;

import com.example.settlement.payment.domain.OrderId;

/** 売上確定に失敗したことの通知(REQ-ORD-005)。人手での対応が必要な状態へ進む。 */
public record PaymentCaptureFailed(OrderId orderId) {
    public PaymentCaptureFailed {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
    }
}

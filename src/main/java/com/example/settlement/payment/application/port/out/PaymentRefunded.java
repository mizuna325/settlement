package com.example.settlement.payment.application.port.out;

import com.example.settlement.payment.domain.OrderId;

/**
 * 返金が完了したことの通知(REQ-ORD-006)。
 *
 * @param fullyRefunded 累計返金額が売上確定額に達したか。注文を REFUNDED と
 *                      PARTIALLY_REFUNDED のどちらへ進めるかの判断に使う
 */
public record PaymentRefunded(OrderId orderId, boolean fullyRefunded) {
    public PaymentRefunded {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
    }
}

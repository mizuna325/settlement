package com.example.settlement.payment.application.port.out;

import com.example.settlement.payment.domain.OrderId;

public record PaymentAuthDeclined(OrderId orderId) {

    public PaymentAuthDeclined {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId can not be null.");
        }
    }

}

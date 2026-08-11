package com.example.settlement.payment.application.port.out;

import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentOperation;
import com.example.settlement.shared.Money;

public interface PspDispatchQueuePort {
    void enqueue(PaymentOperation operation, PaymentId paymentId, Money amount);
}

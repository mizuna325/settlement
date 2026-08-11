package com.example.settlement.payment.application.port.in;

import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.shared.Money;
import com.example.settlement.payment.domain.OrderId;

public interface AuthorizePaymentUseCase {
    PaymentId authorize(OrderId orderId, Money amount);
}

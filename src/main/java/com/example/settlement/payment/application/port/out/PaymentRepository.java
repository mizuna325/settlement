package com.example.settlement.payment.application.port.out;

import java.util.Optional;
import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findById(PaymentId paymentId);

    /** 注文からの返金要求では注文IDしか分からないため、そこから決済を引く(REQ-ORD-009)。 */
    Optional<Payment> findByOrderId(OrderId orderId);
}

package com.example.settlement.payment.application.port.out;

import java.util.Optional;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;

public interface PaymentRepository {
    Payment save(Payment payment);

    Optional<Payment> findById(PaymentId paymentId);
}

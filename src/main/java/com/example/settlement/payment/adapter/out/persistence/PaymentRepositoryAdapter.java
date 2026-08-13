package com.example.settlement.payment.adapter.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;

@Component
class PaymentRepositoryAdapter implements PaymentRepository {

    private final PaymentJdbcRepository paymentJdbcRepository;

    PaymentRepositoryAdapter(PaymentJdbcRepository paymentJdbcRepository) {
        this.paymentJdbcRepository = paymentJdbcRepository;
    }

    @Override
    public Payment save(Payment payment) {
        return paymentJdbcRepository.save(PaymentEntity.from(payment)).toDomain();
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return paymentJdbcRepository.findById(paymentId.paymentId()).map(PaymentEntity::toDomain);
    }
}
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
        PaymentEntity saved = paymentJdbcRepository.save(PaymentEntity.from(payment));
        // 渡された集約にもバージョンを書き戻す(design.md §3)。同一トランザクション内で
        // 同じインスタンスを再度保存する経路があるため、戻り値だけを更新すると
        // 2回目が古いバージョンで更新を試みて楽観ロックに失敗する。
        payment.applyPersistedVersion(saved.version());
        return saved.toDomain();
    }

    @Override
    public Optional<Payment> findById(PaymentId paymentId) {
        return paymentJdbcRepository.findById(paymentId.paymentId()).map(PaymentEntity::toDomain);
    }
}
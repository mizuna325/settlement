package com.example.settlement.payment.application.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.payment.application.port.in.FindPaymentUseCase;
import com.example.settlement.payment.application.port.in.PaymentSummary;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;

/**
 * REQ-PAY-013: 決済の現在状態と金額の内訳を返す。
 *
 * <p>
 * 金額の解釈は集約が持つ({@code authorizedAmount} / {@code capturedAmount} /
 * {@code refundedTotal})。「どの状態なら確定とみなすか」は業務ルールであり、
 * ここで組み立てると同じ判断がアプリケーション層にも散る。
 */
@Service
class FindPaymentService implements FindPaymentUseCase {

    private final PaymentRepository paymentRepository;

    FindPaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentSummary> find(PaymentId paymentId) {
        return paymentRepository.findById(paymentId).map(FindPaymentService::summaryOf);
    }

    private static PaymentSummary summaryOf(Payment payment) {
        return new PaymentSummary(
                payment.getPaymentId(),
                payment.getPaymentStatus(),
                payment.getAmount(),
                payment.authorizedAmount(),
                payment.capturedAmount(),
                payment.refundedTotal());
    }
}

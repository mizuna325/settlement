package com.example.settlement.payment.application.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.payment.application.port.in.RefundPaymentUseCase;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentOperation;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.shared.Money;

/**
 * 注文からの返金要求を受け、PSPへ返金を依頼する(REQ-ORD-009)。
 *
 * <p>
 * 売上確定の未完了(REQ-PAY-007)、累計の超過(REQ-PAY-008)、処理中の重複(REQ-PAY-010)は
 * {@link Payment#requestRefund} が弾く。ここに if 文として書いてはならない(REQ-PAY-012)。
 */
@Service
class RefundPaymentService implements RefundPaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PspDispatchQueuePort pspDispatchQueuePort;
    private final Clock clock;

    RefundPaymentService(PaymentRepository paymentRepository, PspDispatchQueuePort pspDispatchQueuePort, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.pspDispatchQueuePort = pspDispatchQueuePort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void refund(OrderId orderId, Money amount, RefundReason reason) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("no payment exists for the order: " + orderId));

        payment.requestRefund(amount, reason, clock.instant());
        paymentRepository.save(payment);
        pspDispatchQueuePort.enqueue(PaymentOperation.REFUND, payment.getPaymentId(), amount);
    }
}

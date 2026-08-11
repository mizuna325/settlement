package com.example.settlement.payment.application.service;

import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;
import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentOperation;
import org.springframework.transaction.annotation.Transactional;
import com.example.settlement.payment.domain.PaymentId;
import org.springframework.stereotype.Service;
import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.shared.Money;

@Service
class AuthorizePaymentService implements AuthorizePaymentUseCase {

    private final PaymentRepository paymentRepository;
    private final PspDispatchQueuePort pspDispatchQueuePort;

    AuthorizePaymentService(PaymentRepository paymentRepository, PspDispatchQueuePort pspDispatchQueuePort) {
        this.paymentRepository = paymentRepository;
        this.pspDispatchQueuePort = pspDispatchQueuePort;
    }

    @Override
    @Transactional
    public PaymentId authorize(OrderId orderId, Money amount) {
        Payment payment = Payment.create(orderId, amount);
        Payment savedPayment = paymentRepository.save(payment);
        pspDispatchQueuePort.enqueue(PaymentOperation.AUTHORIZE, savedPayment.getPaymentId(),
                savedPayment.getAmount());
        return savedPayment.getPaymentId();
    }
}
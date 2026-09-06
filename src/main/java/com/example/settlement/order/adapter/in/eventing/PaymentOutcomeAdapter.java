package com.example.settlement.order.adapter.in.eventing;

import org.springframework.stereotype.Component;

import com.example.settlement.order.application.port.in.CancelOrderUseCase;
import com.example.settlement.order.application.port.in.ConfirmOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;

@Component
public class PaymentOutcomeAdapter implements PaymentOutcomePort {
    private final ConfirmOrderUseCase confirmOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;

    public PaymentOutcomeAdapter(ConfirmOrderUseCase confirmOrderUseCase, CancelOrderUseCase cancelOrderUseCase) {
        this.confirmOrderUseCase = confirmOrderUseCase;
        this.cancelOrderUseCase = cancelOrderUseCase;
    }

    @Override
    public void authorized(PaymentAuthorized event) {
        OrderId orderId = new OrderId(event.orderId().orderId());
        confirmOrderUseCase.confirm(orderId);
    }

    @Override
    public void declined(PaymentAuthDeclined event) {
        OrderId orderId = new OrderId(event.orderId().orderId());
        cancelOrderUseCase.cancel(orderId);
    }

}

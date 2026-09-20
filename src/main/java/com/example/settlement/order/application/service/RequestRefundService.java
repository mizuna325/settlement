package com.example.settlement.order.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.RequestRefundUseCase;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.payment.application.port.in.RefundPaymentUseCase;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.shared.Money;

/**
 * 返金要求を決済コンテキストへ引き渡す(REQ-ORD-009)。
 *
 * <p>
 * 注文の状態はここでは変えない。返金が成立したかはPSPの結果を待つ必要があり、
 * 確定した時点で {@link RefundOrderService} が Webhook 経由で進める(REQ-ORD-006)。
 */
@Service
class RequestRefundService implements RequestRefundUseCase {

    private final RefundPaymentUseCase refundPaymentUseCase;

    RequestRefundService(RefundPaymentUseCase refundPaymentUseCase) {
        this.refundPaymentUseCase = refundPaymentUseCase;
    }

    @Override
    @Transactional
    public void requestRefund(OrderId orderId, Money amount, String reason) {
        refundPaymentUseCase.refund(
                new com.example.settlement.payment.domain.OrderId(orderId.orderId()),
                amount,
                new RefundReason(reason));
    }
}

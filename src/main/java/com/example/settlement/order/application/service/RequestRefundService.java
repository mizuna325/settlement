package com.example.settlement.order.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
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

    private static final Logger log = LoggerFactory.getLogger(RequestRefundService.class);

    private final RefundPaymentUseCase refundPaymentUseCase;

    RequestRefundService(RefundPaymentUseCase refundPaymentUseCase) {
        this.refundPaymentUseCase = refundPaymentUseCase;
    }

    @Override
    @Transactional
    public void requestRefund(OrderId orderId, Money amount, String reason) {
        try (MDCCloseable scope = MDC.putCloseable("orderId", orderId.orderId().toString())) {
            // 返金は人手が起点になる唯一の操作(REQ-ORD-009)。誰かが要求した事実を
            // 残さないと、この後の一連の処理が何をきっかけに始まったのか分からない。
            // reason は顧客の自由入力なので載せない。必要なら payment_refunds から引ける。
            log.atInfo()
                    .addKeyValue("amount", amount.amount())
                    .addKeyValue("currency", amount.unit().name())
                    .log("accepted the refund request and handed it to the payment context");

            refundPaymentUseCase.refund(
                    new com.example.settlement.payment.domain.OrderId(orderId.orderId()),
                    amount,
                    new RefundReason(reason));
        }
    }
}

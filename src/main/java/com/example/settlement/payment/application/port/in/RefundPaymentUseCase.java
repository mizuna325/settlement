package com.example.settlement.payment.application.port.in;

import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.shared.Money;

/**
 * 注文から返金を要求される入口(REQ-ORD-009)。
 *
 * <p>
 * 注文側が持っているのは注文IDなので、決済の特定はこちらで行う。
 */
public interface RefundPaymentUseCase {
    void refund(OrderId orderId, Money amount, RefundReason reason);
}

package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;
import com.example.settlement.shared.Money;

/**
 * 顧客からの返金要求を受け付ける入口(REQ-ORD-009)。
 *
 * <p>
 * 返金の可否は決済コンテキストの不変条件(REQ-PAY-007/008/010)が判定する。
 */
public interface RequestRefundUseCase {
    void requestRefund(OrderId orderId, Money amount, String reason);
}

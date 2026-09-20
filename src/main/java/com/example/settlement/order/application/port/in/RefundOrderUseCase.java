package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;

/** 返金完了の通知を受けて注文を進める(REQ-ORD-006)。 */
public interface RefundOrderUseCase {
    void refund(OrderId orderId, boolean fullyRefunded);
}

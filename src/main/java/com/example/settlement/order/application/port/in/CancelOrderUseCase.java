package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;

public interface CancelOrderUseCase {
    void cancel(OrderId orderId);
}

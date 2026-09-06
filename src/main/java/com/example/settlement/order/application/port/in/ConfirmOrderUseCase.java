package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;

public interface ConfirmOrderUseCase {
    void confirm(OrderId orderId);
}

package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.OrderLine;
import java.util.List;

public interface CreateOrderUseCase {
    OrderId create(CustomerId customerId, List<OrderLine> orderLines);
}

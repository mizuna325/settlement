package com.example.settlement.order.application.port.out;

import java.util.Optional;

import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

public interface OrderRepository {
    Order save(Order oder);

    Optional<Order> findById(OrderId orderId);
}

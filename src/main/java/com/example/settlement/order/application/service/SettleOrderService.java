package com.example.settlement.order.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.SettleOrderUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class SettleOrderService implements SettleOrderUseCase {
    private final OrderRepository orderRepository;

    SettleOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void settle(OrderId orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.settle();
        orderRepository.save(order);
    }
}

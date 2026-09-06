package com.example.settlement.order.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.CancelOrderUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class CancelOrderService implements CancelOrderUseCase {
    private final OrderRepository orderRepository;

    CancelOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void cancel(OrderId orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.cancel();
        orderRepository.save(order);

    }
}

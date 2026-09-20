package com.example.settlement.order.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.FailOrderSettlementUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class FailOrderSettlementService implements FailOrderSettlementUseCase {
    private final OrderRepository orderRepository;

    FailOrderSettlementService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void failSettlement(OrderId orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.failSettlement();
        orderRepository.save(order);
    }
}

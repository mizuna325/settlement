package com.example.settlement.order.adapter.out.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Component
class OrderRepositoryAdapter implements OrderRepository {

    private final OrderJdbcRepository orderJdbcRepository;

    OrderRepositoryAdapter(OrderJdbcRepository orderJdbcRepository) {
        this.orderJdbcRepository = orderJdbcRepository;
    }

    @Override
    public Order save(Order order) {
        return orderJdbcRepository.save(OrderEntity.from(order)).toDomain();
    }

    @Override
    public Optional<Order> findById(OrderId orderId) {
        return orderJdbcRepository.findById(orderId.orderId()).map(OrderEntity::toDomain);
    }
}
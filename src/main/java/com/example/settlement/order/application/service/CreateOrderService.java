package com.example.settlement.order.application.service;

import com.example.settlement.order.domain.Order;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;

import org.springframework.stereotype.Service;

import com.example.settlement.order.application.port.in.CreateOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.OrderLine;

@Service
class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final AuthorizePaymentUseCase authorizePaymentUseCase;

    public CreateOrderService(OrderRepository orderRepository, AuthorizePaymentUseCase authorizePaymentUseCase) {
        this.orderRepository = orderRepository;
        this.authorizePaymentUseCase = authorizePaymentUseCase;
    }

    @Override
    @Transactional
    public OrderId create(CustomerId customerId, List<OrderLine> orderLines) {
        Order order = Order.createOrder(customerId, orderLines);
        Order updatedOrder = orderRepository.save(order);
        authorizePaymentUseCase.authorize(
                new com.example.settlement.payment.domain.OrderId(updatedOrder.getOrderId().orderId()),
                updatedOrder.getTotalAmount());
        return updatedOrder.getOrderId();
    }
}
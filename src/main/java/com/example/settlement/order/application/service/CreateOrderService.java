package com.example.settlement.order.application.service;

import com.example.settlement.order.domain.Order;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.payment.application.port.in.AuthorizePaymentUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
import org.springframework.stereotype.Service;

import com.example.settlement.order.application.port.in.CreateOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.OrderLine;

@Service
class CreateOrderService implements CreateOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateOrderService.class);

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
        Order savedOrder = orderRepository.save(order);

        // orderId が確定するのは保存の後。ここから与信の依頼までを一つのスコープにする
        // (design.md §8.8)。
        try (MDCCloseable scope = MDC.putCloseable(
                "orderId", savedOrder.getOrderId().orderId().toString())) {
            // サイクルの起点。これが無いと、Relay が動いていない場合に注文が存在したことすら
            // ログに残らない。customerId は顧客の識別子なので載せない。
            log.atInfo()
                    .addKeyValue("amount", savedOrder.getTotalAmount().amount())
                    .addKeyValue("currency", savedOrder.getTotalAmount().unit().name())
                    .addKeyValue("lineCount", orderLines.size())
                    .log("accepted the order and started authorization");

            authorizePaymentUseCase.authorize(
                    new com.example.settlement.payment.domain.OrderId(savedOrder.getOrderId().orderId()),
                    savedOrder.getTotalAmount());
        }
        return savedOrder.getOrderId();
    }
}
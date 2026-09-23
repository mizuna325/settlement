package com.example.settlement.order.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.ConfirmOrderUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class ConfirmOrderService implements ConfirmOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmOrderService.class);

    private final OrderRepository orderRepository;

    ConfirmOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void confirm(OrderId orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.confirm();
        orderRepository.save(order);
        // orderId はMDCに載っている(PaymentOutcomeAdapter)。遷移後の状態だけを足す。
        log.atInfo()
                .addKeyValue("orderStatus", order.getOrderStatus().name())
                .log("与信が成立したため注文を確定した");
    }
}

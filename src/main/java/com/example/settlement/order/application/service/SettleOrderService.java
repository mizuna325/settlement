package com.example.settlement.order.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.SettleOrderUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class SettleOrderService implements SettleOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(SettleOrderService.class);

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
        // 注文の終着点。ここまで来れば人手の介在は不要だったことになる。
        log.atInfo()
                .addKeyValue("orderStatus", order.getOrderStatus().name())
                .log("settled the order because the capture completed");
    }
}

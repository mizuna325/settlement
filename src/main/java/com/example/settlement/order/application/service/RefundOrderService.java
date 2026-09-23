package com.example.settlement.order.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.RefundOrderUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class RefundOrderService implements RefundOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundOrderService.class);

    private final OrderRepository orderRepository;

    RefundOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void refund(OrderId orderId, boolean fullyRefunded) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.refund(fullyRefunded);
        orderRepository.save(order);
        // REQ-ORD-006: 5つの遷移のうち、ここだけが引数で行き先を変える。
        // 判断材料の fullyRefunded と結果の orderStatus を両方残さないと、
        // PARTIALLY_REFUNDED で止まった理由が後から分からない。
        log.atInfo()
                .addKeyValue("fullyRefunded", fullyRefunded)
                .addKeyValue("orderStatus", order.getOrderStatus().name())
                .log("返金が成立したため注文の状態を更新した");
    }
}

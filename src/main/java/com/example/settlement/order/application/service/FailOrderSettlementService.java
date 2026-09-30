package com.example.settlement.order.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.FailOrderSettlementUseCase;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

@Service
class FailOrderSettlementService implements FailOrderSettlementUseCase {

    private static final Logger log = LoggerFactory.getLogger(FailOrderSettlementService.class);

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
        // SETTLEMENT_FAILED は自動で解消しない終端。与信は取れているのに売上が立っていない
        // 状態であり、人手の対応が要る(REQ-ORD-005)。ERROR ではなく WARN にするのは、
        // システムの障害ではなくPSPが返した業務上の結果であるため。
        log.atWarn()
                .addKeyValue("orderStatus", order.getOrderStatus().name())
                .log("flagged the order for manual handling because the capture failed");
    }
}

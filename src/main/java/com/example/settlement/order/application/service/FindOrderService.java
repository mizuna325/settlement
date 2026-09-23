package com.example.settlement.order.application.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.in.FindOrderUseCase;
import com.example.settlement.order.application.port.in.OrderSummary;
import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;

/**
 * REQ-ORD-007: 注文の現在状態を返す。
 *
 * <p>
 * ログは出さない。読むだけで何も変えておらず、「誰かが状態を見た」という事実に
 * 答えるべき問いが無いため(design.md §8.8)。
 */
@Service
class FindOrderService implements FindOrderUseCase {

    private final OrderRepository orderRepository;

    FindOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * {@code readOnly = true} を付ける。書き込みが無いことを宣言でき、
     * ドライバとDBに読み取り専用として扱わせられる。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> find(OrderId orderId) {
        return orderRepository.findById(orderId).map(FindOrderService::summaryOf);
    }

    private static OrderSummary summaryOf(Order order) {
        return new OrderSummary(order.getOrderId(), order.getOrderStatus(), order.getTotalAmount());
    }
}

package com.example.settlement.order.adapter.in.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.settlement.order.application.port.in.CreateOrderUseCase;
import com.example.settlement.order.application.port.in.FindOrderUseCase;
import com.example.settlement.order.application.port.in.RequestRefundUseCase;
import com.example.settlement.order.domain.OrderId;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
class OrderController {

    private final CreateOrderUseCase createOrderUseCase;
    private final RequestRefundUseCase requestRefundUseCase;
    private final FindOrderUseCase findOrderUseCase;

    OrderController(CreateOrderUseCase createOrderUseCase, RequestRefundUseCase requestRefundUseCase,
            FindOrderUseCase findOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
        this.requestRefundUseCase = requestRefundUseCase;
        this.findOrderUseCase = findOrderUseCase;
    }

    /** REQ-ORD-001: 注文を受け付け、同一トランザクション内で与信を開始する。 */
    @PostMapping
    ResponseEntity<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderId orderId = createOrderUseCase.create(request.toCustomerId(), request.toOrderLines());
        return ResponseEntity
                .created(URI.create("/orders/" + orderId.orderId()))
                .body(CreateOrderResponse.from(orderId));
    }

    /**
     * REQ-ORD-007: 注文の現在状態を返す。
     *
     * <p>
     * {@code POST /orders} は注文IDだけを返し、確定するのはPSPの結果が届いた後になる。
     * 呼び出した側が結果を知る手段がこれにあたるため、状態が変わるまで繰り返し呼ばれる
     * 前提で置いている。
     */
    @GetMapping("/{orderId}")
    ResponseEntity<OrderResponse> find(@PathVariable UUID orderId) {
        return findOrderUseCase.find(new OrderId(orderId))
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * REQ-ORD-009: 返金を要求する。
     *
     * <p>
     * 受け付けた時点では返金は成立していない。PSPへ依頼するところまでが同期で、
     * 注文が REFUNDED / PARTIALLY_REFUNDED へ進むのはWebhookが届いた後(REQ-ORD-006)。
     * そのため 202 Accepted を返す。
     */
    @PostMapping("/{orderId}/refunds")
    ResponseEntity<Void> refund(@PathVariable UUID orderId, @Valid @RequestBody RefundOrderRequest request) {
        requestRefundUseCase.requestRefund(new OrderId(orderId), request.toMoney(), request.reason());
        return ResponseEntity.accepted().build();
    }
}
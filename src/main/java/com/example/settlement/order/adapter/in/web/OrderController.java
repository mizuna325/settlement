package com.example.settlement.order.adapter.in.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.settlement.order.application.port.in.CreateOrderUseCase;
import com.example.settlement.order.domain.OrderId;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/orders")
class OrderController {

    private final CreateOrderUseCase createOrderUseCase;

    OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    /** REQ-ORD-001: 注文を受け付け、同一トランザクション内で与信を開始する。 */
    @PostMapping
    ResponseEntity<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderId orderId = createOrderUseCase.create(request.toCustomerId(), request.toOrderLines());
        return ResponseEntity
                .created(URI.create("/orders/" + orderId.orderId()))
                .body(CreateOrderResponse.from(orderId));
    }
}
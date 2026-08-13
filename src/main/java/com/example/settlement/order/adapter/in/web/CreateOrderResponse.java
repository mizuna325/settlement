package com.example.settlement.order.adapter.in.web;

import java.util.UUID;

import com.example.settlement.order.domain.OrderId;

record CreateOrderResponse(UUID orderId) {

    static CreateOrderResponse from(OrderId orderId) {
        return new CreateOrderResponse(orderId.orderId());
    }
}
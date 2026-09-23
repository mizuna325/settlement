package com.example.settlement.order.adapter.in.web;

import java.util.UUID;

import com.example.settlement.order.application.port.in.OrderSummary;

/**
 * REQ-ORD-007 の応答。
 *
 * <p>
 * 通貨を金額と別のフィールドで返す。{@code Money} をそのまま直列化すると
 * {@code unit} という項目名になり、APIの項目名が内部の実装名に引きずられる。
 */
record OrderResponse(UUID orderId, String status, long amount, String currency) {

    static OrderResponse from(OrderSummary summary) {
        return new OrderResponse(
                summary.orderId().orderId(),
                summary.orderStatus().name(),
                summary.totalAmount().amount(),
                summary.totalAmount().unit().name());
    }
}

package com.example.settlement.order.adapter.out.persistence;

import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * order_lines の永続化専用モデル。
 * order_id と line_index は @MappedCollection が書くため、フィールドとして持たない。
 */
@Table("order_lines")
record OrderLineEntity(
        String productId,
        int quantity,
        long amount,
        String currency) {

    static OrderLineEntity from(OrderLine orderLine) {
        return new OrderLineEntity(
                orderLine.productId().productId(),
                orderLine.quantity().quantity(),
                orderLine.amount().amount(),
                orderLine.amount().unit().name());
    }

    OrderLine toDomain() {
        return new OrderLine(
                new ProductId(productId),
                new Quantity(quantity),
                new Money(amount, Currency.valueOf(currency)));
    }
}
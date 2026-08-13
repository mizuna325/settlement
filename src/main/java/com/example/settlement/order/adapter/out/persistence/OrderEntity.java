package com.example.settlement.order.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.order.domain.OrderStatus;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

@Table("orders")
record OrderEntity(@Id UUID orderId,
        @Version long version,
        UUID customerId,
        long totalAmount,
        String currency,
        String status,
        @MappedCollection(idColumn = "order_id", keyColumn = "line_index") //
        List<OrderLineEntity> orderLines) {
    static OrderEntity from(Order order) {
        return new OrderEntity(
                order.getOrderId().orderId(),
                order.getVersion(),
                order.getCustomerId().customerId(),
                order.getTotalAmount().amount(),
                order.getTotalAmount().unit().name(),
                order.getOrderStatus().name(),
                order.getOrderLines().stream().map(OrderLineEntity::from).toList());
    }

    Order toDomain() {
        return Order.reconstruct(
                version,
                new OrderId(orderId),
                new CustomerId(customerId),
                orderLines.stream().map(OrderLineEntity::toDomain).toList(),
                new Money(totalAmount, Currency.valueOf(currency)),
                OrderStatus.valueOf(status));
    }
}

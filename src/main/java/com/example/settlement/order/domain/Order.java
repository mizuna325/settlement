package com.example.settlement.order.domain;

import java.util.List;
import com.example.settlement.shared.Money;

public class Order {
    private long version;
    private final OrderId orderId;
    private final CustomerId customerId;
    private final List<OrderLine> orderLines;
    private final Money totalAmount;
    private OrderStatus orderStatus;

    private Order(CustomerId customerId, List<OrderLine> orderLines) {
        if (customerId == null) {
            throw new IllegalArgumentException("customerId must not be null.");
        }
        if (orderLines == null || orderLines.isEmpty()) {
            throw new IllegalArgumentException("orderLines must not be null or empty.");
        }
        this.version = 0;
        this.orderId = OrderId.generate();
        this.customerId = customerId;
        this.orderLines = List.copyOf(orderLines);
        this.totalAmount = this.orderLines.stream().map(OrderLine::amount).reduce(Money::plus)
                .orElseThrow();
        this.orderStatus = OrderStatus.PENDING;
    }

    private Order(long version, OrderId orderId, CustomerId customerId, List<OrderLine> orderLines, Money totalAmount,
            OrderStatus orderStatus) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("customerId must not be null.");
        }
        if (orderLines == null || orderLines.isEmpty()) {
            throw new IllegalArgumentException("orderLines must not be null or empty.");
        }
        if (totalAmount == null) {
            throw new IllegalArgumentException("totalAmount must not be null.");
        }
        if (orderStatus == null) {
            throw new IllegalArgumentException("orderStatus must not be null.");
        }
        this.version = version;
        this.orderId = orderId;
        this.customerId = customerId;
        this.orderLines = List.copyOf(orderLines);
        this.totalAmount = totalAmount;
        this.orderStatus = orderStatus;
    }

    public static Order createOrder(CustomerId customerId, List<OrderLine> orderLines) {
        return new Order(customerId, orderLines);
    }

    public static Order reconstruct(long version, OrderId orderId, CustomerId customerId, List<OrderLine> orderLines,
            Money totalAmount,
            OrderStatus orderStatus) {
        return new Order(version, orderId, customerId, orderLines, totalAmount, orderStatus);
    }

    private void transitionTo(OrderStatus next) {

        if (!orderStatus.canTransitionTo(next)) {
            throw new IllegalStateException("the current orderStatus can not transition to " + next.toString());
        }
        this.orderStatus = next;

    }

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    public void settle() {
        transitionTo(OrderStatus.SETTLED);
    }

    public void failSettlement() {
        transitionTo(OrderStatus.SETTLEMENT_FAILED);
    }

    public void refund(boolean fullyRefunded) {
        transitionTo(fullyRefunded ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
    }

    public OrderId getOrderId() {
        return this.orderId;
    }

    public CustomerId getCustomerId() {
        return this.customerId;
    }

    public List<OrderLine> getOrderLines() {
        return this.orderLines;
    }

    public Money getTotalAmount() {
        return this.totalAmount;
    }

    public OrderStatus getOrderStatus() {
        return this.orderStatus;
    }

    public long getVersion() {
        return this.version;
    }
}

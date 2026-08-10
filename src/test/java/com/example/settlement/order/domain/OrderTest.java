package com.example.settlement.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

class OrderTest {

    private static CustomerId customerId() {
        return new CustomerId(UUID.randomUUID());
    }

    private static Money jpy(long amount) {
        return new Money(amount, Currency.JPY);
    }

    private static OrderLine orderLine(String productId, int quantity, long amount) {
        return new OrderLine(new ProductId(productId), new Quantity(quantity), jpy(amount));
    }

    /** 任意の状態の注文を直接組み立てる。遷移メソッドを経由しないので、各テストが独立する。 */
    private static Order orderWith(OrderStatus orderStatus) {
        List<OrderLine> orderLines = List.of(orderLine("SKU-1", 1, 1000));
        return Order.reconstruct(0, OrderId.generate(), customerId(), orderLines, jpy(1000), orderStatus);
    }

    @Test
    @DisplayName("REQ-ORD-001: 注文は PENDING で生成される")
    void createdOrderIsPending() {
        Order order = Order.createOrder(customerId(), List.of(orderLine("SKU-1", 2, 100)));

        assertEquals(OrderStatus.PENDING, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-001: 合計金額は明細の金額の和になる")
    void totalAmountIsSumOfOrderLines() {
        Order order = Order.createOrder(customerId(), List.of(
                orderLine("SKU-1", 1, 100),
                orderLine("SKU-2", 3, 250),
                orderLine("SKU-3", 2, 30)));

        assertEquals(jpy(380), order.getTotalAmount());
    }

    @Test
    @DisplayName("REQ-ORD-001: 明細が空の注文は生成できない")
    void orderWithoutOrderLinesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.createOrder(customerId(), List.of()));
    }

    @Test
    @DisplayName("REQ-ORD-001: 顧客IDのない注文は生成できない")
    void orderWithoutCustomerIdIsRejected() {
        List<OrderLine> orderLines = List.of(orderLine("SKU-1", 1, 100));

        assertThrows(IllegalArgumentException.class,
                () -> Order.createOrder(null, orderLines));
    }

    @Test
    @DisplayName("REQ-ORD-002: 与信成功で PENDING から CONFIRMED へ遷移する")
    void confirmMovesPendingToConfirmed() {
        Order order = orderWith(OrderStatus.PENDING);

        order.confirm();

        assertEquals(OrderStatus.CONFIRMED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-003: 与信拒否で PENDING から CANCELLED へ遷移する")
    void cancelMovesPendingToCancelled() {
        Order order = orderWith(OrderStatus.PENDING);

        order.cancel();

        assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-004: 売上確定完了で CONFIRMED から SETTLED へ遷移する")
    void settleMovesConfirmedToSettled() {
        Order order = orderWith(OrderStatus.CONFIRMED);

        order.settle();

        assertEquals(OrderStatus.SETTLED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-005: 売上確定失敗で CONFIRMED から SETTLEMENT_FAILED へ遷移する")
    void failSettlementMovesConfirmedToSettlementFailed() {
        Order order = orderWith(OrderStatus.CONFIRMED);

        order.failSettlement();

        assertEquals(OrderStatus.SETTLEMENT_FAILED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-006: 全額返金で REFUNDED へ遷移する")
    void fullRefundMovesToRefunded() {
        Order order = orderWith(OrderStatus.SETTLED);

        order.refund(true);

        assertEquals(OrderStatus.REFUNDED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-006: 一部返金で PARTIALLY_REFUNDED へ遷移する")
    void partialRefundMovesToPartiallyRefunded() {
        Order order = orderWith(OrderStatus.SETTLED);

        order.refund(false);

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-006: 部分返金を重ねた後に全額へ達すると REFUNDED へ遷移する")
    void refundAfterPartialRefundMovesToRefunded() {
        Order order = orderWith(OrderStatus.PARTIALLY_REFUNDED);

        order.refund(true);

        assertEquals(OrderStatus.REFUNDED, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-008: 定義されていない遷移は拒否され、状態は変化しない")
    void undefinedTransitionIsRejectedAndKeepsStatus() {
        Order order = orderWith(OrderStatus.PENDING);

        assertThrows(IllegalStateException.class, order::settle);
        assertEquals(OrderStatus.PENDING, order.getOrderStatus());
    }

    @Test
    @DisplayName("REQ-ORD-008: 終端状態からの遷移は拒否され、状態は変化しない")
    void transitionFromTerminalStatusIsRejectedAndKeepsStatus() {
        Order order = orderWith(OrderStatus.CANCELLED);

        assertThrows(IllegalStateException.class, order::confirm);
        assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
    }

    @Test
    @DisplayName("明細のリストは呼び出し元から変更できない")
    void orderLinesAreUnmodifiable() {
        Order order = orderWith(OrderStatus.PENDING);

        assertThrows(UnsupportedOperationException.class,
                () -> order.getOrderLines().add(orderLine("SKU-9", 1, 1)));
    }
}

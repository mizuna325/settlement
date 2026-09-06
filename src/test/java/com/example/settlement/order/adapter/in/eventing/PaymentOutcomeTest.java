package com.example.settlement.order.adapter.in.eventing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.example.settlement.order.application.port.out.OrderRepository;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.Order;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * 与信結果の通知が注文の状態に反映されるまでの結合テスト。
 *
 * <p>
 * payment 側が定義した {@link PaymentOutcomePort} を型として注入する。
 * order が実装を提供していなければ起動時点で解決に失敗するため、
 * DIによる結線(design.md §1 の②)そのものの検証を兼ねる。
 */
@SpringBootTest
@Transactional
class PaymentOutcomeTest {

    @Autowired
    PaymentOutcomePort paymentOutcomePort;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    JdbcClient jdbcClient;

    private OrderId pendingOrder() {
        Order order = Order.createOrder(
                new CustomerId(UUID.randomUUID()),
                List.of(new OrderLine(new ProductId("SKU-1"), new Quantity(1), new Money(1000, Currency.JPY))));
        return orderRepository.save(order).getOrderId();
    }

    private static com.example.settlement.payment.domain.OrderId paymentOrderId(OrderId orderId) {
        return new com.example.settlement.payment.domain.OrderId(orderId.orderId());
    }

    private String statusOf(OrderId orderId) {
        return jdbcClient.sql("SELECT status FROM orders WHERE order_id = :id")
                .param("id", orderId.orderId())
                .query(String.class)
                .single();
    }

    @Test
    @DisplayName("REQ-ORD-002: 与信成功の通知で注文が CONFIRMED になる")
    void authorizedConfirmsOrder() {
        OrderId orderId = pendingOrder();

        paymentOutcomePort.authorized(new PaymentAuthorized(paymentOrderId(orderId)));

        assertEquals("CONFIRMED", statusOf(orderId));
    }

    @Test
    @DisplayName("REQ-ORD-003: 与信拒否の通知で注文が CANCELLED になる")
    void declinedCancelsOrder() {
        OrderId orderId = pendingOrder();

        paymentOutcomePort.declined(new PaymentAuthDeclined(paymentOrderId(orderId)));

        assertEquals("CANCELLED", statusOf(orderId));
    }

    /**
     * 適用できない通知を握りつぶさないことを固定する。REQ-PSP-007 の「200 OK を返す」判断は
     * Webhook の入口(PspWebhookController)の責務であり、ここで例外を消すと
     * 入口がその判断をできなくなる。
     */
    @Test
    @DisplayName("PENDING でない注文への与信結果は適用できず例外になる")
    void outcomeOnNonPendingOrderIsRejected() {
        OrderId orderId = pendingOrder();
        paymentOutcomePort.declined(new PaymentAuthDeclined(paymentOrderId(orderId)));

        assertThrows(IllegalStateException.class,
                () -> paymentOutcomePort.authorized(new PaymentAuthorized(paymentOrderId(orderId))));
    }

    @Test
    @DisplayName("存在しない注文への通知は例外になる")
    void outcomeForUnknownOrderIsRejected() {
        com.example.settlement.payment.domain.OrderId unknown = new com.example.settlement.payment.domain.OrderId(
                UUID.randomUUID());

        assertThrows(RuntimeException.class,
                () -> paymentOutcomePort.authorized(new PaymentAuthorized(unknown)));
    }
}

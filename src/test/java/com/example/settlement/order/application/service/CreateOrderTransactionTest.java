package com.example.settlement.order.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.settlement.order.application.port.in.CreateOrderUseCase;
import com.example.settlement.order.domain.CustomerId;
import com.example.settlement.order.domain.OrderLine;
import com.example.settlement.order.domain.ProductId;
import com.example.settlement.order.domain.Quantity;
import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * REQ-PSP-001 の検証。
 * 本物のロールバックを観測するため、テスト側では @Transactional を張らない。
 */
@SpringBootTest
class CreateOrderTransactionTest {

    @MockitoBean
    PspDispatchQueuePort pspDispatchQueuePort;

    @Autowired
    CreateOrderUseCase createOrderUseCase;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("REQ-PSP-001: ディスパッチの永続化が失敗した場合、注文と決済も永続化されない")
    void dispatchFailureRollsBackEverything() {
        doThrow(new IllegalStateException("dispatch failed"))
                .when(pspDispatchQueuePort).enqueue(any(), any(), any());

        CustomerId customerId = new CustomerId(UUID.randomUUID());
        List<OrderLine> orderLines = List.of(new OrderLine(
                new ProductId("SKU-1"), new Quantity(1), new Money(1000, Currency.JPY)));

        assertThrows(IllegalStateException.class,
                () -> createOrderUseCase.create(customerId, orderLines));

        long orderCount = jdbcClient
                .sql("SELECT count(*) FROM orders WHERE customer_id = :id")
                .param("id", customerId.customerId())
                .query(Long.class).single();
        assertEquals(0L, orderCount);

        long paymentCount = jdbcClient
                .sql("""
                        SELECT count(*) FROM payments p
                          JOIN orders o ON o.order_id = p.order_id
                         WHERE o.customer_id = :id
                        """)
                .param("id", customerId.customerId())
                .query(Long.class).single();
        assertEquals(0L, paymentCount);
    }
}

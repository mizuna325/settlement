package com.example.settlement.order.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * POST /orders の結合テスト。
 * テストのトランザクションで実行し、終了時にロールバックする。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CreateOrderApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("REQ-ORD-001: POST /orders で Order が PENDING、Payment が AUTHORIZING、Outbox に1件積まれる")
    void createOrder() throws Exception {
        UUID customerId = UUID.randomUUID();

        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "customerId": "%s",
                          "lines": [
                            { "productId": "SKU-1", "quantity": 2, "amount": 1000, "currency": "JPY" },
                            { "productId": "SKU-2", "quantity": 1, "amount": 500,  "currency": "JPY" }
                          ]
                        }
                        """.formatted(customerId)))
                .andExpect(status().isCreated());

        String orderStatus = jdbcClient
                .sql("SELECT status FROM orders WHERE customer_id = :id")
                .param("id", customerId)
                .query(String.class).single();
        assertEquals("PENDING", orderStatus);

        long totalAmount = jdbcClient
                .sql("SELECT total_amount FROM orders WHERE customer_id = :id")
                .param("id", customerId)
                .query(Long.class).single();
        assertEquals(1500L, totalAmount);

        long lineCount = jdbcClient
                .sql("""
                        SELECT count(*) FROM order_lines l
                          JOIN orders o ON o.order_id = l.order_id
                         WHERE o.customer_id = :id
                        """)
                .param("id", customerId)
                .query(Long.class).single();
        assertEquals(2L, lineCount);

        String paymentStatus = jdbcClient
                .sql("""
                        SELECT p.status FROM payments p
                          JOIN orders o ON o.order_id = p.order_id
                         WHERE o.customer_id = :id
                        """)
                .param("id", customerId)
                .query(String.class).single();
        assertEquals("AUTHORIZING", paymentStatus);

        String authorizationStatus = jdbcClient
                .sql("""
                        SELECT a.status FROM payment_authorizations a
                          JOIN payments p ON p.payment_id = a.payment_id
                          JOIN orders o ON o.order_id = p.order_id
                         WHERE o.customer_id = :id
                        """)
                .param("id", customerId)
                .query(String.class).single();
        assertEquals("PENDING", authorizationStatus);

        long dispatchCount = jdbcClient
                .sql("""
                        SELECT count(*) FROM payment_psp_dispatch_events d
                          JOIN payments p ON p.payment_id = d.payment_id
                          JOIN orders o ON o.order_id = p.order_id
                         WHERE o.customer_id = :id
                           AND d.status = 'PENDING'
                           AND d.operation = 'AUTHORIZE'
                           AND d.attempts = 0
                           AND d.claimed_at IS NULL
                        """)
                .param("id", customerId)
                .query(Long.class).single();
        assertEquals(1L, dispatchCount);
    }
}

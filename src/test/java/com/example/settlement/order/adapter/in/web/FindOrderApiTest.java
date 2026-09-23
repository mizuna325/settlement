package com.example.settlement.order.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REQ-ORD-007: 注文の照会。
 *
 * <p>
 * この経路があることで、{@code POST /orders} を呼んだ側が結果を知れるようになる。
 * 受け付けは 201 で注文IDしか返らず、確定はPSPの結果が届いた後になるため。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FindOrderApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void clearTables() {
        jdbcClient.sql("DELETE FROM payment_psp_dispatch_events").update();
        jdbcClient.sql("DELETE FROM payment_refunds").update();
        jdbcClient.sql("DELETE FROM payment_captures").update();
        jdbcClient.sql("DELETE FROM payment_authorizations").update();
        jdbcClient.sql("DELETE FROM payments").update();
        jdbcClient.sql("DELETE FROM order_lines").update();
        jdbcClient.sql("DELETE FROM orders").update();
    }

    private UUID placeOrder() throws Exception {
        String body = mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "customerId": "%s",
                          "lines": [
                            { "productId": "SKU-1", "quantity": 2, "amount": 400, "currency": "JPY" },
                            { "productId": "SKU-2", "quantity": 1, "amount": 600, "currency": "JPY" }
                          ]
                        }
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(body.replaceAll(".*\"orderId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    @Test
    @DisplayName("REQ-ORD-007: 受け付けた注文の状態と金額を返す")
    void returnsTheCurrentStateAndAmount() throws Exception {
        UUID orderId = placeOrder();

        mockMvc.perform(get("/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                // 受け付けた直後はまだ与信の結果が返っていない(REQ-ORD-001)。
                .andExpect(jsonPath("$.status").value("PENDING"))
                // 400 + 600。明細の amount の単純合計であり、quantity は乗じられない。
                // つまり amount は単価ではなく明細ごとの小計を表す(Order#totalAmount)。
                .andExpect(jsonPath("$.amount").value(1000))
                .andExpect(jsonPath("$.currency").value("JPY"));
    }

    @Test
    @DisplayName("存在しない注文IDは 404 を返す")
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(get("/orders/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UUIDとして読めないIDは 400 を返す")
    void malformedIdIsRejected() throws Exception {
        // 404 ではない。存在しないのではなく、IDの形式が違う。
        mockMvc.perform(get("/orders/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}

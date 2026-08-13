package com.example.settlement.pspsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FakePspControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PspIdempotencyKeyStore idempotencyKeyStore;

    private static String body() {
        return """
                { "paymentId": "%s", "amount": 1000, "currency": "JPY" }
                """.formatted(UUID.randomUUID());
    }

    private void authorize(UUID idempotencyKey) throws Exception {
        mockMvc.perform(post("/psp/authorize")
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
                .andExpect(status().isAccepted());
    }

    private long storedKeyCount(UUID idempotencyKey) {
        return jdbcClient
                .sql("SELECT count(*) FROM payment_psp_idempotency_keys WHERE dispatch_event_id = :id")
                .param("id", idempotencyKey)
                .query(Long.class).single();
    }

    @Test
    @DisplayName("REQ-SIM-001: 与信要求に対し、結果を含まない 202 Accepted を即座に返す")
    void returnsAcceptedWithoutResult() throws Exception {
        mockMvc.perform(post("/psp/authorize")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("REQ-SIM-005: 同一の冪等性キーで再送されても新たに処理せず 202 を返す")
    void deduplicatesByIdempotencyKey() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();

        authorize(idempotencyKey);
        authorize(idempotencyKey);

        assertEquals(1L, storedKeyCount(idempotencyKey));
    }

    @Test
    @DisplayName("REQ-SIM-005: 冪等性キーの記録は初回のみ true を返す")
    void registerIfAbsentReturnsTrueOnlyOnce() {
        UUID idempotencyKey = UUID.randomUUID();
        Instant now = Instant.now();

        assertEquals(true, idempotencyKeyStore.registerIfAbsent(idempotencyKey, now));
        assertEquals(false, idempotencyKeyStore.registerIfAbsent(idempotencyKey, now));
    }

    @Test
    @DisplayName("REQ-NFR-006: 保持期間を過ぎた冪等性キーは削除される")
    void deletesExpiredKeys() {
        UUID expired = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        Instant now = Instant.now();

        idempotencyKeyStore.registerIfAbsent(expired, now.minus(Duration.ofHours(25)));
        idempotencyKeyStore.registerIfAbsent(fresh, now);

        idempotencyKeyStore.deleteExpired(now, Duration.ofHours(24));

        assertEquals(0L, storedKeyCount(expired));
        assertEquals(1L, storedKeyCount(fresh));
    }
}

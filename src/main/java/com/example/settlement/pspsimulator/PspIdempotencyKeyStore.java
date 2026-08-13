package com.example.settlement.pspsimulator;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PSP側の冪等性キーストア(REQ-SIM-005)。
 *
 * 本来はPSP側のシステムが持つ状態だが、演習のため settlement と同じDBに置く。
 * プロセスの再起動をまたいで残る必要がある。メモリ上に持つと、障害注入で
 * settlement を落としたときにキーも消え、再送が「初回」として二重処理されてしまい、
 * 「回収が安全なのは冪等性キーがあるから」(design.md §5.1)を実演できない。
 */
@Component
class PspIdempotencyKeyStore {

    private final JdbcClient jdbcClient;

    PspIdempotencyKeyStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * キーを記録し、初めて受け付けた場合のみ true を返す。
     *
     * 判定を主キー制約に委ねているため、SELECT してから INSERT する場合と違い、
     * 同一キーが同時に届いても片方しか true にならない。
     */
    boolean registerIfAbsent(UUID dispatchEventId, Instant receivedAt) {
        int inserted = jdbcClient.sql("""
                INSERT INTO payment_psp_idempotency_keys (dispatch_event_id, created_at)
                VALUES (:dispatchEventId, :createdAt)
                ON CONFLICT (dispatch_event_id) DO NOTHING
                """)
                .param("dispatchEventId", dispatchEventId)
                .param("createdAt", OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    /** REQ-NFR-006: 保持期間(24時間)を過ぎたキーを削除する。 */
    int deleteExpired(Instant now, Duration retention) {
        return jdbcClient.sql("""
                DELETE FROM payment_psp_idempotency_keys WHERE created_at < :threshold
                """)
                .param("threshold", OffsetDateTime.ofInstant(now.minus(retention), ZoneOffset.UTC))
                .update();
    }
}

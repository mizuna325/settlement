package com.example.settlement.payment.adapter.out.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentOperation;
import com.example.settlement.shared.Money;

/**
 * Transactional Outbox のレコード。集約ではないため対応するドメインモデルを持たない(design.md §3)。
 * dispatch_event_id は PSP への Idempotency-Key として送信する。
 */
@Table("payment_psp_dispatch_events")
record PspDispatchEventEntity(@Id UUID dispatchEventId,
        UUID paymentId,
        String operation,
        long amount,
        String currency,
        String status,
        short attempts,
        Instant claimedAt,
        Instant nextAttemptAt,
        Instant createdAt) {

    static PspDispatchEventEntity pending(PaymentOperation operation, PaymentId paymentId, Money amount,
            Instant createdAt) {
        return new PspDispatchEventEntity(
                UUID.randomUUID(),
                paymentId.paymentId(),
                operation.name(),
                amount.amount(),
                amount.unit().name(),
                PspDispatchStatus.PENDING.name(),
                (short) 0,
                null,
                createdAt, // 初回は待たずに送る
                createdAt);
    }
}
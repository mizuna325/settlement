package com.example.settlement.payment.adapter.out.outbox;

import java.time.Instant;

import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Component;

import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentOperation;
import com.example.settlement.shared.Money;

@Component
class PspDispatchQueueAdapter implements PspDispatchQueuePort {

    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final DispatchTraceContext traceContext;

    PspDispatchQueueAdapter(JdbcAggregateTemplate jdbcAggregateTemplate, DispatchTraceContext traceContext) {
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.traceContext = traceContext;
    }

    /**
     * トレースコンテキストの取得はここで行い、{@link PspDispatchQueuePort} には持ち込まない。
     * 追跡は技術的な横断関心であって、アプリケーション層が意識すべき契約ではないため。
     */
    @Override
    public void enqueue(PaymentOperation operation, PaymentId paymentId, Money amount) {
        jdbcAggregateTemplate.insert(
                PspDispatchEventEntity.pending(operation, paymentId, amount, Instant.now(),
                        traceContext.capture()));
    }
}
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

    PspDispatchQueueAdapter(JdbcAggregateTemplate jdbcAggregateTemplate) {
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
    }

    @Override
    public void enqueue(PaymentOperation operation, PaymentId paymentId, Money amount) {
        jdbcAggregateTemplate.insert(
                PspDispatchEventEntity.pending(operation, paymentId, amount, Instant.now()));
    }
}
package com.example.settlement.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.payment.domain.Refund;
import com.example.settlement.payment.domain.RefundId;
import com.example.settlement.payment.domain.RefundReason;
import com.example.settlement.payment.domain.RefundStatus;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/**
 * payment_refunds の永続化専用モデル。
 * payment_id と refund_index は @MappedCollection が書く。
 */
@Table("payment_refunds")
record RefundEntity(@Id UUID refundId,
        long amount,
        String currency,
        String pspReference,
        String status,
        String reason,
        Instant requestedAt) {

    static RefundEntity from(Refund refund) {
        return new RefundEntity(
                refund.getRefundId().refundId(),
                refund.getAmount().amount(),
                refund.getAmount().unit().name(),
                refund.getPspReference(),
                refund.getRefundStatus().name(),
                refund.getReason().reason(),
                refund.getRequestedAt());
    }

    Refund toDomain() {
        return Refund.reconstruct(
                new RefundId(refundId),
                new Money(amount, Currency.valueOf(currency)),
                new RefundReason(reason),
                requestedAt,
                pspReference,
                RefundStatus.valueOf(status));
    }
}

package com.example.settlement.payment.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.payment.domain.OrderId;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentStatus;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/** Payment集約の永続化専用モデル。 */
@Table("payments")
record PaymentEntity(@Id UUID paymentId,
        @Version long version,
        UUID orderId,
        long amount,
        String currency,
        String status,
        @MappedCollection(idColumn = "payment_id") AuthorizationEntity authorization) {

    static PaymentEntity from(Payment payment) {
        return new PaymentEntity(
                payment.getPaymentId().paymentId(),
                payment.getVersion(),
                payment.getOrderId().orderId(),
                payment.getAmount().amount(),
                payment.getAmount().unit().name(),
                payment.getPaymentStatus().name(),
                AuthorizationEntity.from(payment.getAuthorization()));
    }

    Payment toDomain() {
        return Payment.reconstruct(
                version,
                new PaymentId(paymentId),
                new OrderId(orderId),
                new Money(amount, Currency.valueOf(currency)),
                PaymentStatus.valueOf(status),
                authorization.toDomain());
    }
}
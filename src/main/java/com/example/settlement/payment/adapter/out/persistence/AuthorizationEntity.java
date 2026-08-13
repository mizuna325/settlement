package com.example.settlement.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.payment.domain.Authorization;
import com.example.settlement.payment.domain.AuthorizationId;
import com.example.settlement.payment.domain.AuthorizationStatus;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/** payment_authorizations の永続化専用モデル。payment_id は @MappedCollection が書く。 */
@Table("payment_authorizations")
record AuthorizationEntity(@Id UUID authorizationId,
        long amount,
        String currency,
        String pspReference,
        String status,
        Instant authorizedAt,
        Instant expiresAt) {

    static AuthorizationEntity from(Authorization authorization) {
        return new AuthorizationEntity(
                authorization.getAuthorizationId().authorizationId(),
                authorization.getAmount().amount(),
                authorization.getAmount().unit().name(),
                authorization.getPspReference(),
                authorization.getAuthorizationStatus().name(),
                authorization.getAuthorizedAt(),
                authorization.getExpiresAt());
    }

    Authorization toDomain() {
        return Authorization.reconstruct(
                new AuthorizationId(authorizationId),
                new Money(amount, Currency.valueOf(currency)),
                pspReference,
                AuthorizationStatus.valueOf(status),
                authorizedAt,
                expiresAt);
    }
}
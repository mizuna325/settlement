package com.example.settlement.payment.domain;

import java.time.Instant;

import com.example.settlement.shared.Money;

public class Authorization {
    private final AuthorizationId authorizationId;
    private final Money amount;
    private String pspReference;
    private AuthorizationStatus authorizationStatus;
    private Instant authorizedAt;
    private Instant expiresAt;

    private Authorization(AuthorizationId authorizationId, Money amount, String pspReference,
            AuthorizationStatus authorizationStatus, Instant authorizedAt, Instant expiresAt) {
        if (authorizationId == null) {
            throw new IllegalArgumentException("authorizationId must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        if (authorizationStatus == null) {
            throw new IllegalArgumentException("authorizationStatus must not be null.");
        }
        this.authorizationId = authorizationId;
        this.amount = amount;
        this.pspReference = pspReference;
        this.authorizationStatus = authorizationStatus;
        this.authorizedAt = authorizedAt;
        this.expiresAt = expiresAt;
    }

    /** 与信を新規に開始する。PSPへ依頼した直後の状態(REQ-PAY-001)。 */
    public static Authorization create(AuthorizationId authorizationId, Money amount) {
        return new Authorization(authorizationId, amount, null, AuthorizationStatus.PENDING, null, null);
    }

    /** 永続化から復元する。 */
    public static Authorization reconstruct(AuthorizationId authorizationId, Money amount, String pspReference,
            AuthorizationStatus authorizationStatus, Instant authorizedAt, Instant expiresAt) {
        return new Authorization(authorizationId, amount, pspReference, authorizationStatus, authorizedAt, expiresAt);
    }

    public AuthorizationId getAuthorizationId() {
        return this.authorizationId;
    }

    public Money getAmount() {
        return this.amount;
    }

    public String getPspReference() {
        return this.pspReference;
    }

    public AuthorizationStatus getAuthorizationStatus() {
        return this.authorizationStatus;
    }

    public Instant getAuthorizedAt() {
        return this.authorizedAt;
    }

    public Instant getExpiresAt() {
        return this.expiresAt;
    }
}

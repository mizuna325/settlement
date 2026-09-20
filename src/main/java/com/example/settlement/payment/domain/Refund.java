package com.example.settlement.payment.domain;

import java.time.Instant;

import com.example.settlement.shared.Money;

public class Refund {
    private final RefundId refundId;
    private final Money amount;
    private final RefundReason reason;
    private final Instant requestedAt;
    private String pspReference;
    private RefundStatus refundStatus;

    private Refund(RefundId refundId, Money amount, RefundReason reason, Instant requestedAt, String pspReference,
            RefundStatus refundStatus) {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null.");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null.");
        }
        if (refundStatus == null) {
            throw new IllegalArgumentException("refundStatus must not be null.");
        }
        this.refundId = refundId;
        this.amount = amount;
        this.reason = reason;
        this.requestedAt = requestedAt;
        this.pspReference = pspReference;
        this.refundStatus = refundStatus;
    }

    /** 返金を新規に要求する。PSPへ依頼した直後の状態(REQ-ORD-009)。 */
    public static Refund create(RefundId refundId, Money amount, RefundReason reason, Instant requestedAt) {
        return new Refund(refundId, amount, reason, requestedAt, null, RefundStatus.PENDING);
    }

    /** 永続化から復元する。 */
    public static Refund reconstruct(RefundId refundId, Money amount, RefundReason reason, Instant requestedAt,
            String pspReference, RefundStatus refundStatus) {
        return new Refund(refundId, amount, reason, requestedAt, pspReference, refundStatus);
    }

    public RefundId getRefundId() {
        return this.refundId;
    }

    public Money getAmount() {
        return this.amount;
    }

    public RefundReason getReason() {
        return this.reason;
    }

    public Instant getRequestedAt() {
        return this.requestedAt;
    }

    public String getPspReference() {
        return this.pspReference;
    }

    public RefundStatus getRefundStatus() {
        return this.refundStatus;
    }

    private void transitionTo(RefundStatus next) {
        if (this.refundStatus.canTransitionTo(next)) {
            this.refundStatus = next;
        } else {
            throw new IllegalStateException("the current RefundStatus can not transition to " + next.toString());
        }
    }

    void refund(String pspReference) {
        if (pspReference == null || pspReference.isBlank()) {
            throw new IllegalArgumentException("pspReference cannot be null or empty");
        }
        transitionTo(RefundStatus.REFUNDED);
        this.pspReference = pspReference;
    }

    void fail() {
        transitionTo(RefundStatus.FAILED);
    }

    boolean isPending() {
        return this.refundStatus == RefundStatus.PENDING;
    }

    boolean isRefunded() {
        return this.refundStatus == RefundStatus.REFUNDED;
    }
}

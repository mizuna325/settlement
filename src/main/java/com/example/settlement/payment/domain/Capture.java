package com.example.settlement.payment.domain;

import java.time.Instant;

import com.example.settlement.shared.Money;

public class Capture {
    private final CaptureId captureId;
    private final Money amount;
    private String pspReference;
    private CaptureStatus captureStatus;
    private Instant capturedAt;

    private Capture(CaptureId captureId, Money amount, String pspReference, CaptureStatus captureStatus,
            Instant capturedAt) {
        if (captureId == null) {
            throw new IllegalArgumentException("captureId must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        if (captureStatus == null) {
            throw new IllegalArgumentException("captureStatus must not be null.");
        }
        this.captureId = captureId;
        this.amount = amount;
        this.pspReference = pspReference;
        this.captureStatus = captureStatus;
        this.capturedAt = capturedAt;
    }

    /** 売上確定を新規に開始する。PSPへ依頼した直後の状態(REQ-PAY-004)。 */
    public static Capture create(CaptureId captureId, Money amount) {
        return new Capture(captureId, amount, null, CaptureStatus.PENDING, null);
    }

    /** 永続化から復元する。 */
    public static Capture reconstruct(CaptureId captureId, Money amount, String pspReference,
            CaptureStatus captureStatus, Instant capturedAt) {
        return new Capture(captureId, amount, pspReference, captureStatus, capturedAt);
    }

    public CaptureId getCaptureId() {
        return this.captureId;
    }

    public Money getAmount() {
        return this.amount;
    }

    public String getPspReference() {
        return this.pspReference;
    }

    public CaptureStatus getCaptureStatus() {
        return this.captureStatus;
    }

    public Instant getCapturedAt() {
        return this.capturedAt;
    }

    private void transitionTo(CaptureStatus next) {
        if (this.captureStatus.canTransitionTo(next)) {
            this.captureStatus = next;
        } else {
            throw new IllegalStateException("the current CaptureStatus can not transition to " + next.toString());
        }
    }

    void capture(String pspReference, Instant capturedAt) {
        if (pspReference == null || pspReference.isBlank()) {
            throw new IllegalArgumentException("pspReference cannot be null or empty");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("capturedAt cannot be null");
        }
        transitionTo(CaptureStatus.CAPTURED);
        this.pspReference = pspReference;
        this.capturedAt = capturedAt;
    }

    void fail() {
        transitionTo(CaptureStatus.FAILED);
    }

    boolean isPending() {
        return this.captureStatus == CaptureStatus.PENDING;
    }
}

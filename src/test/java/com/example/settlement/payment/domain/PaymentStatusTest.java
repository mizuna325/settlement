package com.example.settlement.payment.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    /** requirements.md §2.2 の遷移図。テスト側に独立して持つことで、enum の変更を検出する。 */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PaymentStatus.AUTHORIZING, Set.of(PaymentStatus.AUTHORIZED, PaymentStatus.AUTH_DECLINED),
            PaymentStatus.AUTHORIZED, Set.of(PaymentStatus.CAPTURING),
            PaymentStatus.CAPTURING, Set.of(PaymentStatus.CAPTURED, PaymentStatus.CAPTURE_FAILED),
            PaymentStatus.CAPTURED, Set.of(PaymentStatus.REFUNDING),
            PaymentStatus.REFUNDING,
            Set.of(PaymentStatus.REFUNDED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.CAPTURED),
            PaymentStatus.PARTIALLY_REFUNDED, Set.of(PaymentStatus.REFUNDING));

    private static final List<PaymentStatus> TERMINAL_STATUSES = List.of(
            PaymentStatus.AUTH_DECLINED, PaymentStatus.CAPTURE_FAILED, PaymentStatus.REFUNDED);

    @Test
    @DisplayName("REQ-PAY-002/003: AUTHORIZING からは AUTHORIZED と AUTH_DECLINED へ遷移できる")
    void authorizingCanTransitionToAuthorizedOrDeclined() {
        assertTrue(PaymentStatus.AUTHORIZING.canTransitionTo(PaymentStatus.AUTHORIZED));
        assertTrue(PaymentStatus.AUTHORIZING.canTransitionTo(PaymentStatus.AUTH_DECLINED));
    }

    @Test
    @DisplayName("REQ-PAY-004: AUTHORIZED からは CAPTURING へ遷移できる")
    void authorizedCanTransitionToCapturing() {
        assertTrue(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURING));
    }

    @Test
    @DisplayName("CAPTURING からは CAPTURED と CAPTURE_FAILED へ遷移できる")
    void capturingCanTransitionToCapturedOrFailed() {
        assertTrue(PaymentStatus.CAPTURING.canTransitionTo(PaymentStatus.CAPTURED));
        assertTrue(PaymentStatus.CAPTURING.canTransitionTo(PaymentStatus.CAPTURE_FAILED));
    }

    @Test
    @DisplayName("CAPTURED からは REFUNDING へ遷移できる")
    void capturedCanTransitionToRefunding() {
        assertTrue(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDING));
    }

    @Test
    @DisplayName("REQ-PAY-009: REFUNDING からは REFUNDED と PARTIALLY_REFUNDED へ遷移できる")
    void refundingCanTransitionToRefundedOrPartiallyRefunded() {
        assertTrue(PaymentStatus.REFUNDING.canTransitionTo(PaymentStatus.REFUNDED));
        assertTrue(PaymentStatus.REFUNDING.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    @DisplayName("REQ-PAY-011: 返金失敗時に REFUNDING から直前の状態へ戻れる")
    void refundingCanFallBackToPreviousStatus() {
        assertTrue(PaymentStatus.REFUNDING.canTransitionTo(PaymentStatus.CAPTURED));
        assertTrue(PaymentStatus.REFUNDING.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    @DisplayName("PARTIALLY_REFUNDED からは追加の返金要求で REFUNDING へ遷移できる")
    void partiallyRefundedCanTransitionToRefunding() {
        assertTrue(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.REFUNDING));
    }

    @Test
    @DisplayName("終端状態からはいかなる遷移も許可されない")
    void terminalStatusesRejectAllTransitions() {
        for (PaymentStatus from : TERMINAL_STATUSES) {
            for (PaymentStatus to : PaymentStatus.values()) {
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }

    @Test
    @DisplayName("定義されていない遷移は許可されない")
    void undefinedTransitionsAreRejected() {
        for (PaymentStatus from : PaymentStatus.values()) {
            Set<PaymentStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
            for (PaymentStatus to : PaymentStatus.values()) {
                if (allowed.contains(to)) {
                    continue;
                }
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }
}

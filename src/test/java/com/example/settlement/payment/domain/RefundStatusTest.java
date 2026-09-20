package com.example.settlement.payment.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundStatusTest {

    private static final List<RefundStatus> TERMINAL_STATUSES = List.of(
            RefundStatus.REFUNDED, RefundStatus.FAILED);

    @Test
    @DisplayName("REQ-PAY-009/011: PENDING からは REFUNDED と FAILED へ遷移できる")
    void pendingCanTransitionToRefundedOrFailed() {
        assertTrue(RefundStatus.PENDING.canTransitionTo(RefundStatus.REFUNDED));
        assertTrue(RefundStatus.PENDING.canTransitionTo(RefundStatus.FAILED));
    }

    @Test
    @DisplayName("PENDING へ戻ることはできない")
    void pendingIsNotReachable() {
        for (RefundStatus from : RefundStatus.values()) {
            assertFalse(from.canTransitionTo(RefundStatus.PENDING), from + " -> PENDING は許可されない");
        }
    }

    @Test
    @DisplayName("終端状態からはいかなる遷移も許可されない")
    void terminalStatusesRejectAllTransitions() {
        for (RefundStatus from : TERMINAL_STATUSES) {
            for (RefundStatus to : RefundStatus.values()) {
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }
}

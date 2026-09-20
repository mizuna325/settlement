package com.example.settlement.payment.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureStatusTest {

    private static final List<CaptureStatus> TERMINAL_STATUSES = List.of(
            CaptureStatus.CAPTURED, CaptureStatus.FAILED);

    @Test
    @DisplayName("REQ-ORD-004/005: PENDING からは CAPTURED と FAILED へ遷移できる")
    void pendingCanTransitionToCapturedOrFailed() {
        assertTrue(CaptureStatus.PENDING.canTransitionTo(CaptureStatus.CAPTURED));
        assertTrue(CaptureStatus.PENDING.canTransitionTo(CaptureStatus.FAILED));
    }

    @Test
    @DisplayName("PENDING へ戻ることはできない")
    void pendingIsNotReachable() {
        for (CaptureStatus from : CaptureStatus.values()) {
            assertFalse(from.canTransitionTo(CaptureStatus.PENDING), from + " -> PENDING は許可されない");
        }
    }

    @Test
    @DisplayName("終端状態からはいかなる遷移も許可されない")
    void terminalStatusesRejectAllTransitions() {
        for (CaptureStatus from : TERMINAL_STATUSES) {
            for (CaptureStatus to : CaptureStatus.values()) {
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }
}

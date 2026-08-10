package com.example.settlement.order.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStatusTest {

    /** requirements.md §2.1 の遷移図。テスト側に独立して持つことで、enum の変更を検出する。 */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SETTLED, OrderStatus.SETTLEMENT_FAILED),
            OrderStatus.SETTLED, Set.of(OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED),
            OrderStatus.PARTIALLY_REFUNDED, Set.of(OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED));

    private static final List<OrderStatus> TERMINAL_STATUSES = List.of(
            OrderStatus.CANCELLED, OrderStatus.SETTLEMENT_FAILED, OrderStatus.REFUNDED);

    @Test
    @DisplayName("REQ-ORD-002/003: PENDING からは CONFIRMED と CANCELLED へ遷移できる")
    void pendingCanTransitionToConfirmedOrCancelled() {
        boolean fromPendingToConfirmed = OrderStatus.PENDING.canTransitionTo(OrderStatus.CONFIRMED);
        boolean fromPendingToCancelled = OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED);

        assertTrue(fromPendingToConfirmed);
        assertTrue(fromPendingToCancelled);
    }

    @Test
    @DisplayName("REQ-ORD-004/005: CONFIRMED からは SETTLED と SETTLEMENT_FAILED へ遷移できる")
    void confirmedCanTransitionToSettledOrSettlementFailed() {
        boolean fromConfirmedToSettled = OrderStatus.CONFIRMED.canTransitionTo(OrderStatus.SETTLED);
        boolean fromConfirmedToSettlementFailed = OrderStatus.CONFIRMED
                .canTransitionTo(OrderStatus.SETTLEMENT_FAILED);

        assertTrue(fromConfirmedToSettled);
        assertTrue(fromConfirmedToSettlementFailed);
    }

    @Test
    @DisplayName("REQ-ORD-006: SETTLED からは PARTIALLY_REFUNDED と REFUNDED へ遷移できる")
    void settledCanTransitionToRefundStates() {
        boolean fromSettledToPartiallyRefunded = OrderStatus.SETTLED
                .canTransitionTo(OrderStatus.PARTIALLY_REFUNDED);
        boolean fromSettledToRefunded = OrderStatus.SETTLED.canTransitionTo(OrderStatus.REFUNDED);

        assertTrue(fromSettledToPartiallyRefunded);
        assertTrue(fromSettledToRefunded);
    }

    @Test
    @DisplayName("REQ-ORD-006: PARTIALLY_REFUNDED からは PARTIALLY_REFUNDED と REFUNDED へ遷移できる")
    void partiallyRefundedCanTransitionToItselfOrRefunded() {
        boolean fromPartiallyRefundedToItself = OrderStatus.PARTIALLY_REFUNDED
                .canTransitionTo(OrderStatus.PARTIALLY_REFUNDED);
        boolean fromPartiallyRefundedToRefunded = OrderStatus.PARTIALLY_REFUNDED
                .canTransitionTo(OrderStatus.REFUNDED);

        assertTrue(fromPartiallyRefundedToItself);
        assertTrue(fromPartiallyRefundedToRefunded);
    }

    @Test
    @DisplayName("REQ-ORD-008: 終端状態からはいかなる遷移も許可されない")
    void terminalStatesRejectAllTransitions() {
        for (OrderStatus from : TERMINAL_STATUSES) {
            for (OrderStatus to : OrderStatus.values()) {
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }

    @Test
    @DisplayName("REQ-ORD-008: 定義されていない遷移は許可されない")
    void undefinedTransitionsAreRejected() {
        for (OrderStatus from : OrderStatus.values()) {
            Set<OrderStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(from, Set.of());
            for (OrderStatus to : OrderStatus.values()) {
                if (allowed.contains(to)) {
                    continue;
                }
                assertFalse(from.canTransitionTo(to), from + " -> " + to + " は許可されない");
            }
        }
    }
}

package com.example.settlement.order.adapter.in.web;

import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** REQ-ORD-009: 返金額(全額または一部)と理由を受け取る。 */
record RefundOrderRequest(@Positive long amount,
        @NotNull Currency currency,
        @NotBlank String reason) {

    Money toMoney() {
        return new Money(amount, currency);
    }
}

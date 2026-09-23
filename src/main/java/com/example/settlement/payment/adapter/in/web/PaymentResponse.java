package com.example.settlement.payment.adapter.in.web;

import java.util.UUID;

import com.example.settlement.payment.application.port.in.PaymentSummary;

/**
 * REQ-PAY-013 の応答。
 *
 * <p>
 * 金額はすべて同じ通貨になるため、通貨は1つだけ返す。決済は1つの注文に紐づき、
 * 与信・売上確定・返金はいずれもその金額から派生するため、通貨が混ざることはない。
 */
record PaymentResponse(
        UUID paymentId,
        String status,
        long amount,
        long authorizedAmount,
        long capturedAmount,
        long refundedTotal,
        String currency) {

    static PaymentResponse from(PaymentSummary summary) {
        return new PaymentResponse(
                summary.paymentId().paymentId(),
                summary.paymentStatus().name(),
                summary.amount().amount(),
                summary.authorizedAmount().amount(),
                summary.capturedAmount().amount(),
                summary.refundedTotal().amount(),
                summary.amount().unit().name());
    }
}

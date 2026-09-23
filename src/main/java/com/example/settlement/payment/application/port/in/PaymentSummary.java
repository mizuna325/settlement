package com.example.settlement.payment.application.port.in;

import com.example.settlement.payment.domain.PaymentId;
import com.example.settlement.payment.domain.PaymentStatus;
import com.example.settlement.shared.Money;

/**
 * REQ-PAY-013 が返す決済の現在状態。
 *
 * <p>
 * 金額はいずれも<strong>確定した額</strong>で、依頼した額ではない。結果待ちや失敗の場合は0になる。
 * 押さえられていない額を押さえたように見せないため(Payment#authorizedAmount)。
 *
 * @param amount         決済の依頼額。下3つと比べることで、どこまで進んだかが分かる
 * @param authorizedAmount 与信が成立した額。成立していなければ0
 * @param capturedAmount   売上が確定した額。確定していなければ0
 * @param refundedTotal    確定済みの返金累計。処理中の返金は含まない
 */
public record PaymentSummary(
        PaymentId paymentId,
        PaymentStatus paymentStatus,
        Money amount,
        Money authorizedAmount,
        Money capturedAmount,
        Money refundedTotal) {
}

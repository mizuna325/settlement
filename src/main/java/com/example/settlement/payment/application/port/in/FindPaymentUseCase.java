package com.example.settlement.payment.application.port.in;

import java.util.Optional;

import com.example.settlement.payment.domain.PaymentId;

/**
 * REQ-PAY-013: 決済の現在状態を照会する。
 *
 * <p>
 * 注文側の照会(REQ-ORD-007)が「注文がどうなったか」を返すのに対し、こちらは
 * 内訳を返す。与信は通ったが売上確定で落ちた、返金がいくら戻っている、といった
 * 調査に要る値はこちらにしか無い。
 */
public interface FindPaymentUseCase {

    Optional<PaymentSummary> find(PaymentId paymentId);
}

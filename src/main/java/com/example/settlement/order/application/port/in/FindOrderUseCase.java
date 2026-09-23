package com.example.settlement.order.application.port.in;

import java.util.Optional;

import com.example.settlement.order.domain.OrderId;

/**
 * REQ-ORD-007: 注文の現在状態を照会する。
 *
 * <p>
 * {@code POST /orders} は 201 で注文IDだけを返し、確定するのはPSPの結果が届いた後になる
 * (REQ-ORD-002/004)。呼び出した側が結果を知る手段がこれにあたる。
 *
 * <p>
 * 見つからない場合に例外ではなく {@link Optional} を返す。照会で対象が無いことは
 * 異常ではなく、そのまま 404 に対応する事実であるため。応答の組み立ては adapter が行う。
 */
public interface FindOrderUseCase {

    Optional<OrderSummary> find(OrderId orderId);
}

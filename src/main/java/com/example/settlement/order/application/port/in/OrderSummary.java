package com.example.settlement.order.application.port.in;

import com.example.settlement.order.domain.OrderId;
import com.example.settlement.order.domain.OrderStatus;
import com.example.settlement.shared.Money;

/**
 * REQ-ORD-007 が返す注文の現在状態。
 *
 * <p>
 * 集約そのものを adapter へ渡さない。集約には状態を変える操作が並んでおり、
 * 照会の戻り値として外へ出すと、読むだけのはずの経路から遷移を呼べてしまう。
 * ここで必要な値だけに絞る。
 *
 * @param totalAmount 注文金額(REQ-ORD-007 の「金額」)。返金されても減らない。
 *                    返金の累計は決済側の照会(REQ-PAY-013)が持つ
 */
public record OrderSummary(OrderId orderId, OrderStatus orderStatus, Money totalAmount) {
}

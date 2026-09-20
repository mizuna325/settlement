package com.example.settlement.order.adapter.in.eventing;

import org.springframework.stereotype.Component;

import com.example.settlement.order.application.port.in.CancelOrderUseCase;
import com.example.settlement.order.application.port.in.ConfirmOrderUseCase;
import com.example.settlement.order.application.port.in.FailOrderSettlementUseCase;
import com.example.settlement.order.application.port.in.SettleOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentCaptureFailed;
import com.example.settlement.payment.application.port.out.PaymentCaptured;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;

/**
 * payment からの結果通知を order のUseCaseへ繋ぐ。
 *
 * <p>
 * payment が定義した port.out をここで実装することで、payment → order の依存を作らずに
 * 結果を伝える(design.md §1)。order から見ると外から駆動される入口なので adapter.in に置く。
 * 集約のロードと保存、トランザクション境界はUseCaseの実装側が持ち、ここは
 * payment の OrderId を order の OrderId へ詰め替えて委譲するだけに留める。
 */
@Component
class PaymentOutcomeAdapter implements PaymentOutcomePort {
    private final ConfirmOrderUseCase confirmOrderUseCase;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final SettleOrderUseCase settleOrderUseCase;
    private final FailOrderSettlementUseCase failOrderSettlementUseCase;

    PaymentOutcomeAdapter(ConfirmOrderUseCase confirmOrderUseCase, CancelOrderUseCase cancelOrderUseCase,
            SettleOrderUseCase settleOrderUseCase, FailOrderSettlementUseCase failOrderSettlementUseCase) {
        this.confirmOrderUseCase = confirmOrderUseCase;
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.settleOrderUseCase = settleOrderUseCase;
        this.failOrderSettlementUseCase = failOrderSettlementUseCase;
    }

    @Override
    public void authorized(PaymentAuthorized event) {
        confirmOrderUseCase.confirm(orderIdOf(event.orderId()));
    }

    @Override
    public void declined(PaymentAuthDeclined event) {
        cancelOrderUseCase.cancel(orderIdOf(event.orderId()));
    }

    @Override
    public void captured(PaymentCaptured event) {
        settleOrderUseCase.settle(orderIdOf(event.orderId()));
    }

    @Override
    public void captureFailed(PaymentCaptureFailed event) {
        failOrderSettlementUseCase.failSettlement(orderIdOf(event.orderId()));
    }

    /** payment 側の OrderId は order 側とは別の型。ここが両者のモデルの境目になる。 */
    private static OrderId orderIdOf(com.example.settlement.payment.domain.OrderId orderId) {
        return new OrderId(orderId.orderId());
    }
}

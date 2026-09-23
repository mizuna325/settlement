package com.example.settlement.order.adapter.in.eventing;

import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
import org.springframework.stereotype.Component;

import com.example.settlement.order.application.port.in.CancelOrderUseCase;
import com.example.settlement.order.application.port.in.ConfirmOrderUseCase;
import com.example.settlement.order.application.port.in.FailOrderSettlementUseCase;
import com.example.settlement.order.application.port.in.RefundOrderUseCase;
import com.example.settlement.order.application.port.in.SettleOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;
import com.example.settlement.payment.application.port.out.PaymentCaptureFailed;
import com.example.settlement.payment.application.port.out.PaymentCaptured;
import com.example.settlement.payment.application.port.out.PaymentOutcomePort;
import com.example.settlement.payment.application.port.out.PaymentRefunded;

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
    private final RefundOrderUseCase refundOrderUseCase;

    PaymentOutcomeAdapter(ConfirmOrderUseCase confirmOrderUseCase, CancelOrderUseCase cancelOrderUseCase,
            SettleOrderUseCase settleOrderUseCase, FailOrderSettlementUseCase failOrderSettlementUseCase,
            RefundOrderUseCase refundOrderUseCase) {
        this.confirmOrderUseCase = confirmOrderUseCase;
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.settleOrderUseCase = settleOrderUseCase;
        this.failOrderSettlementUseCase = failOrderSettlementUseCase;
        this.refundOrderUseCase = refundOrderUseCase;
    }

    @Override
    public void authorized(PaymentAuthorized event) {
        withOrderId(event.orderId(), confirmOrderUseCase::confirm);
    }

    @Override
    public void declined(PaymentAuthDeclined event) {
        withOrderId(event.orderId(), cancelOrderUseCase::cancel);
    }

    @Override
    public void captured(PaymentCaptured event) {
        withOrderId(event.orderId(), settleOrderUseCase::settle);
    }

    @Override
    public void captureFailed(PaymentCaptureFailed event) {
        withOrderId(event.orderId(), failOrderSettlementUseCase::failSettlement);
    }

    @Override
    public void refunded(PaymentRefunded event) {
        withOrderId(event.orderId(),
                orderId -> refundOrderUseCase.refund(orderId, event.fullyRefunded()));
    }

    /**
     * 型を詰め替えたうえで、処理中の全行に載るよう orderId をMDCへ置く(design.md §8.8)。
     *
     * <p>
     * ここが order コンテキストの入口であり、orderId が確定する最初の地点でもある。
     * 個々のUseCaseで置くと5箇所に散り、置き忘れても誰も気付かない。
     *
     * <p>
     * 呼び出し元のスレッドにはすでに payment 側の paymentId と eventId が載っている。
     * 上書きではなく追加なので、この区間のログには3つとも載る。
     */
    private void withOrderId(com.example.settlement.payment.domain.OrderId source,
            java.util.function.Consumer<OrderId> action) {
        OrderId orderId = orderIdOf(source);
        try (MDCCloseable scope = MDC.putCloseable("orderId", orderId.orderId().toString())) {
            action.accept(orderId);
        }
    }

    /** payment 側の OrderId は order 側とは別の型。ここが両者のモデルの境目になる。 */
    private static OrderId orderIdOf(com.example.settlement.payment.domain.OrderId orderId) {
        return new OrderId(orderId.orderId());
    }
}

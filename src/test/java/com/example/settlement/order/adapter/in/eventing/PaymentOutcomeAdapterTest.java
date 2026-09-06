package com.example.settlement.order.adapter.in.eventing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.settlement.order.application.port.in.CancelOrderUseCase;
import com.example.settlement.order.application.port.in.ConfirmOrderUseCase;
import com.example.settlement.order.domain.OrderId;
import com.example.settlement.payment.application.port.out.PaymentAuthDeclined;
import com.example.settlement.payment.application.port.out.PaymentAuthorized;

/**
 * Adapter の責務は「payment の OrderId を order の OrderId に詰め替えて、対応する
 * UseCase へ渡す」ことだけである。ここではその委譲だけを見る。
 * 実際に状態が変わることは {@link PaymentOutcomeTest} で確認する。
 */
class PaymentOutcomeAdapterTest {

    private final ConfirmOrderUseCase confirmOrderUseCase = mock(ConfirmOrderUseCase.class);
    private final CancelOrderUseCase cancelOrderUseCase = mock(CancelOrderUseCase.class);
    private final PaymentOutcomeAdapter adapter = new PaymentOutcomeAdapter(confirmOrderUseCase, cancelOrderUseCase);

    private static com.example.settlement.payment.domain.OrderId paymentOrderId(UUID orderId) {
        return new com.example.settlement.payment.domain.OrderId(orderId);
    }

    @Test
    @DisplayName("REQ-ORD-002: 与信成功の通知を受けると注文の確定を依頼する")
    void authorizedConfirmsTheOrder() {
        UUID orderId = UUID.randomUUID();

        adapter.authorized(new PaymentAuthorized(paymentOrderId(orderId)));

        verify(confirmOrderUseCase).confirm(new OrderId(orderId));
        verifyNoInteractions(cancelOrderUseCase);
    }

    @Test
    @DisplayName("REQ-ORD-003: 与信拒否の通知を受けると注文の取消を依頼する")
    void declinedCancelsTheOrder() {
        UUID orderId = UUID.randomUUID();

        adapter.declined(new PaymentAuthDeclined(paymentOrderId(orderId)));

        verify(cancelOrderUseCase).cancel(new OrderId(orderId));
        verifyNoInteractions(confirmOrderUseCase);
    }
}

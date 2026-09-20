package com.example.settlement.payment.application.service;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.example.settlement.payment.application.port.out.PaymentRepository;
import com.example.settlement.payment.application.port.out.PspDispatchQueuePort;
import com.example.settlement.payment.domain.Payment;
import com.example.settlement.payment.domain.PaymentOperation;

/**
 * 売上確定をPSPへ依頼する(REQ-PAY-004)。
 *
 * <p>
 * port.in を持たない内部専用のサービス。order から呼ばれる入口ではなく、
 * {@link HandlePspWebhookService} が与信成功を確定させたのと同一トランザクション内で呼ぶ
 * (design.md §2)。出荷等の業務トリガーは待たない([ADR-0001](../../../../../../../../docs/adr/0001-exclude-void-from-scope.md))。
 *
 * <p>
 * トランザクションは呼び出し元が張る。単独で使う入口がない以上、ここで境界を作る意味がない。
 */
@Service
class CapturePaymentService {

    private final PaymentRepository paymentRepository;
    private final PspDispatchQueuePort pspDispatchQueuePort;

    CapturePaymentService(PaymentRepository paymentRepository, PspDispatchQueuePort pspDispatchQueuePort) {
        this.paymentRepository = paymentRepository;
        this.pspDispatchQueuePort = pspDispatchQueuePort;
    }

    /**
     * 与信額の全額を確定する。
     *
     * <p>
     * 与信額超過・有効期限切れ・処理中の重複は {@link Payment#capture} が弾く(REQ-PAY-012)。
     * ここに if 文として書いてはならない。
     */
    void capture(Payment payment, Instant now) {
        payment.capture(payment.getAmount(), now);
        paymentRepository.save(payment);
        pspDispatchQueuePort.enqueue(PaymentOperation.CAPTURE, payment.getPaymentId(), payment.getAmount());
    }
}

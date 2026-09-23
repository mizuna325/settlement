package com.example.settlement.payment.adapter.in.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.settlement.payment.application.port.in.FindPaymentUseCase;
import com.example.settlement.payment.domain.PaymentId;

/**
 * 決済の照会(REQ-PAY-013)。
 *
 * <p>
 * 決済を作る入口はここに無い。決済は注文から始まるものであり、外から直接
 * 作られることはないため(design.md §1)。payment 側のHTTPの入口は
 * この照会と、PSPからのWebhook({@code adapter.in.webhook})の2つだけになる。
 */
@RestController
@RequestMapping("/payments")
class PaymentController {

    private final FindPaymentUseCase findPaymentUseCase;

    PaymentController(FindPaymentUseCase findPaymentUseCase) {
        this.findPaymentUseCase = findPaymentUseCase;
    }

    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentResponse> find(@PathVariable UUID paymentId) {
        return findPaymentUseCase.find(new PaymentId(paymentId))
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

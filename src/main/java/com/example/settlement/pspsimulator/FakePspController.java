package com.example.settlement.pspsimulator;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演習用のPSPスタブ。実プロダクトなら別リポジトリ・別サービスにあたる(design.md §2)。
 */
@RestController
@RequestMapping("/psp")
class FakePspController {

    private static final Logger log = LoggerFactory.getLogger(FakePspController.class);
    private final WebhookDispatcher webhookDispatcher;
    private final PspIdempotencyKeyStore idempotencyKeyStore;

    FakePspController(PspIdempotencyKeyStore idempotencyKeyStore, WebhookDispatcher webhookDispatcher) {
        this.idempotencyKeyStore = idempotencyKeyStore;
        this.webhookDispatcher = webhookDispatcher;
    }

    /**
     * REQ-SIM-001: 結果を含まない 202 Accepted のみを即座に返す。
     * 与信の可否(REQ-SIM-003)はWebhookで通知するため、ステップ3で実装する。
     */
    @PostMapping("/authorize")
    ResponseEntity<Void> authorize(@RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody AuthorizeRequest request) {

        if (!idempotencyKeyStore.registerIfAbsent(idempotencyKey, Instant.now())) {
            // REQ-SIM-005: 受付済みのキー。新たな処理を行わず 202 を返す。
            log.info("受付済みの冪等性キーのため処理しない key={}", idempotencyKey);
            return ResponseEntity.accepted().build();
        }

        log.info("与信要求を受け付けた key={} paymentId={} amount={} {}",
                idempotencyKey, request.paymentId(), request.amount(), request.currency());
        webhookDispatcher.dispatchAuthorizationResult(request.paymentId(), request.amount());
        return ResponseEntity.accepted().build();
    }

    record AuthorizeRequest(UUID paymentId, long amount, String currency) {
    }
}

package com.example.settlement.pspsimulator;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * REQ-SIM-007: 送信済みの通知を手動で送り直す。
     *
     * <p>
     * 本物のPSPが管理画面に持つ再送機能にあたる。ここでは受信側の重複排除(REQ-PSP-006)を
     * 実演するために置いている。保持していた本文をそのまま送るので eventId は変わらず、
     * 受信側は状態を変えずに 200 を返す。
     *
     * @return 未送信の決済なら 404、受信側が受理したら 200、拒否したら 502
     */
    @PostMapping("/webhooks/resend/{paymentId}")
    ResponseEntity<Void> resend(@PathVariable UUID paymentId) {
        return webhookDispatcher.payloadFor(paymentId)
                .map(body -> webhookDispatcher.send(body)
                        ? ResponseEntity.ok().<Void>build()
                        : ResponseEntity.status(HttpStatus.BAD_GATEWAY).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record AuthorizeRequest(UUID paymentId, long amount, String currency) {
    }
}

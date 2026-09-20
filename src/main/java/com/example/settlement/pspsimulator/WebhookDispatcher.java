package com.example.settlement.pspsimulator;

import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final PspSimulatorProperty pspSimulatorProperty;
    private final TaskScheduler taskScheduler;
    private final RestClient restClient;

    /**
     * 手動再送(REQ-SIM-007)のために、送信した本文を決済ごとに覚えておく。
     * 本物のPSPは永続化するが、演習では再起動をまたがない範囲で足りる(ステップ3の決定事項)。
     * リクエストスレッドが書き、スケジューラのスレッドが読むため並行なMapを使う。
     */
    private final Map<UUID, String> sentPayloads = new ConcurrentHashMap<>();

    WebhookDispatcher(PspSimulatorProperty pspSimulatorProperty, TaskScheduler taskScheduler) {
        this.pspSimulatorProperty = pspSimulatorProperty;
        this.taskScheduler = taskScheduler;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(pspSimulatorProperty.webhookConnectTimeout());
        requestFactory.setReadTimeout(pspSimulatorProperty.webhookReadTimeout());

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    void dispatchAuthorizationResult(UUID paymentId, long amount) {
        String result = (amount % 100 == 99) ? "DECLINED" : "AUTHORIZED";
        String eventId = UUID.randomUUID().toString();
        String pspReference = UUID.randomUUID().toString();
        String body = """
                {"eventId":"%s","paymentId":"%s","pspReference":"%s","status":"%s"}"""
                .formatted(
                        "evt-" + eventId,
                        paymentId,
                        "psp-" + pspReference,
                        result);
        sentPayloads.put(paymentId, body);
        taskScheduler.schedule(() -> send(body), Instant.now().plus(randomDelay()));

    }

    /** 手動再送のために、その決済へ送った本文を返す。 */
    Optional<String> payloadFor(UUID paymentId) {
        return Optional.ofNullable(sentPayloads.get(paymentId));
    }

    /**
     * 署名を付けて送る。
     *
     * <p>
     * 署名の t は送信のたびに取り直す。原本の値を使い回すと、再送が許容時間
     * (settlement.psp.webhook-signature-tolerance)を過ぎた時点で自分の署名で弾かれる。
     * 本文は保持していたものをそのまま送るため eventId は変わらず、受信側では重複になる。
     *
     * @return 受信側が 2xx で受理したか
     */
    boolean send(String body) {
        try {
            long t = Instant.now().getEpochSecond();
            String signature = hmacSha256Hex(t + "." + body, pspSimulatorProperty.webhookSecret());
            restClient.post()
                    .uri(pspSimulatorProperty.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Psp-Signature", "t=%d,v1=%s".formatted(t, signature))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // スケジューラは Runnable が投げた例外を握り潰すため、ここで記録しないと無言で消える。
            log.warn("Webhookの送信に失敗した body={}", body, e);
            return false;
        }
    }

    private Duration randomDelay() {
        long min = this.pspSimulatorProperty.webhookDelayMin().toMillis();
        long max = this.pspSimulatorProperty.webhookDelayMax().toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private static String hmacSha256Hex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            // シークレットの設定ミス。リクエストごとに変わるものではないため起動時の不備に等しい。
            throw new IllegalStateException("Webhookの署名を計算できない", e);
        }
    }
}
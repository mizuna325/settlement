package com.example.settlement.pspsimulator;

import java.util.HexFormat;
import java.util.UUID;
import static java.nio.charset.StandardCharsets.UTF_8;

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
        taskScheduler.schedule(() -> send(body), Instant.now().plus(randomDelay()));

    }

    private void send(String body) {

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
        } catch (Exception e) {
            log.warn("HTTPリクエストが失敗", e);
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
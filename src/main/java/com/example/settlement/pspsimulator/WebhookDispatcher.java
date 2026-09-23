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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
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

    /**
     * @param builder 自動設定された {@code RestClient.Builder} を受け取る。自前に作ると
     *                観測機能が組み込まれず、traceparent ヘッダが付かない(design.md §8)。
     */
    WebhookDispatcher(PspSimulatorProperty pspSimulatorProperty, TaskScheduler taskScheduler,
            RestClient.Builder builder) {
        this.pspSimulatorProperty = pspSimulatorProperty;
        this.taskScheduler = taskScheduler;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(pspSimulatorProperty.webhookConnectTimeout());
        requestFactory.setReadTimeout(pspSimulatorProperty.webhookReadTimeout());

        this.restClient = builder
                .requestFactory(requestFactory)
                .build();
    }

    /** REQ-SIM-003: 金額の下2桁が 99 なら拒否。ランダムにしない。 */
    void dispatchAuthorizationResult(UUID paymentId, long amount) {
        dispatch(paymentId, (amount % 100 == 99) ? "DECLINED" : "AUTHORIZED");
    }

    /** REQ-SIM-004: 金額の下2桁が 98 なら失敗。ランダムにしない。 */
    void dispatchCaptureResult(UUID paymentId, long amount) {
        dispatch(paymentId, (amount % 100 == 98) ? "CAPTURE_FAILED" : "CAPTURED");
    }

    /**
     * 返金の可否。要件に定めがないため、与信・売上確定と同じ形で金額の下2桁を使う
     * (97 なら失敗)。ランダムにしないのは、E2Eテストの再現性を保つため。
     */
    void dispatchRefundResult(UUID paymentId, long amount) {
        dispatch(paymentId, (amount % 100 == 97) ? "REFUND_FAILED" : "REFUNDED");
    }

    private void dispatch(UUID paymentId, String status) {
        // eventId と pspReference はPSPが採番するもの。送信ごとに新しい値になる。
        String body = """
                {"eventId":"%s","paymentId":"%s","pspReference":"%s","status":"%s"}"""
                .formatted(
                        "evt-" + UUID.randomUUID(),
                        paymentId,
                        "psp-" + UUID.randomUUID(),
                        status);
        sentPayloads.put(paymentId, body);

        // 送信は TaskScheduler の別スレッドで、しかも1〜5秒後に走る。スレッドローカルに
        // 置かれたトレースコンテキストはそのままでは越えないため、いまの文脈を捕まえて
        // Runnable に被せる(design.md §8.1 の境界③)。
        // これをしないと、Webhook が元の注文とは無関係な新しいトレースとして記録される。
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
        // 波括弧で囲んで Runnable にする。send は boolean を返すため、式のままだと
        // Callable<Boolean> と解釈されて schedule に渡せない。戻り値は手動再送でのみ使う。
        Runnable task = snapshot.wrap(() -> {
            send(paymentId, body);
        });
        taskScheduler.schedule(task, Instant.now().plus(randomDelay()));
    }

    /**
     * 手動再送のために、その決済へ最後に送った本文を返す。
     *
     * <p>
     * 1決済につき最新の1件だけを保持する。与信の後に売上確定を送れば、残るのは後者になる。
     * 運用上ほしいのは「直近の結果をもう一度届ける」ことなので、これで足りる。
     */
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
     * @param paymentId ログに載せる識別子。本文はPSPの通知内容そのものであり、
     *                  丸ごと出すと量とPIIの両面で扱いにくくなるため出さない(design.md §8.8)
     * @return 受信側が 2xx で受理したか
     */
    boolean send(UUID paymentId, String body) {
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
            log.atWarn().setCause(e)
                    .addKeyValue("paymentId", paymentId.toString())
                    .log("Webhookの送信に失敗した");
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
package com.example.settlement.payment.adapter.in.webhook;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.settlement.payment.application.port.in.HandlePspWebhookUseCase;
import com.example.settlement.payment.application.port.in.PspWebhookNotification;
import com.example.settlement.payment.domain.PaymentId;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/payment/webhook")
class PspWebhookController {

    private final WebhookProperty webhookProperty;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final HandlePspWebhookUseCase handlePspWebhookUseCase;
    private final static Pattern expectedSignature = Pattern.compile("t=(\\d{1,18}),v1=([0-9a-f]{64})");

    PspWebhookController(WebhookProperty webhookProperty, Clock clock, ObjectMapper objectMapper,
            HandlePspWebhookUseCase handlePspWebhookUseCase) {
        this.webhookProperty = webhookProperty;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.handlePspWebhookUseCase = handlePspWebhookUseCase;
    }

    @PostMapping
    ResponseEntity<Void> handle(
            @RequestHeader(value = "X-Psp-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        if (!isSignatureValid(signature, rawBody)) {
            return ResponseEntity.status(401).build();
        }

        PspWebhookRequest request = objectMapper.readValue(rawBody, PspWebhookRequest.class);

        // この通知の処理中に出る全行へ識別子を載せる(design.md §8.8)。
        // 個々の行で addKeyValue するのではなくMDCに置くのは、どの行から読み始めても
        // 「どの決済のどの通知か」が分かるようにするため。
        // putCloseable は close で remove する。スレッドは使い回されるため、残すと
        // 無関係なリクエストのログに前の値が載る。
        try (MDCCloseable eventId = MDC.putCloseable("eventId", request.eventId());
                MDCCloseable paymentId = MDC.putCloseable("paymentId", request.paymentId().toString())) {

            // 適用できたか、重複か、適用できなかったかによらず 200 を返す。
            // エラーを返すとPSPが再送を繰り返すため(REQ-PSP-006, REQ-PSP-007)。
            // 適用できなかった理由は、それを知る HandlePspWebhookService 側が WARN に残す。
            handlePspWebhookUseCase.handle(new PspWebhookNotification(
                    request.eventId(),
                    new PaymentId(request.paymentId()),
                    request.status(),
                    request.pspReference()));
        }

        return ResponseEntity.ok().build();
    }

    /**
     * REQ-PSP-005: 正しい送信元から、許容時間内に署名されたものかを検証する。
     *
     * <p>
     * 失敗の理由は呼び出し側へ返さない。応答は401の一種類だけであり、
     * 送信元に形式・時刻・鍵のどれが誤っているかを伝える利点がないため。
     */
    private boolean isSignatureValid(String signature, String rawBody) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        Matcher matcher = expectedSignature.matcher(signature);
        if (!matcher.matches()) {
            return false;
        }
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(matcher.group(1)));
        if (Duration.between(signedAt, clock.instant())
                .compareTo(webhookProperty.webhookSignatureTolerance()) > 0) {
            return false;
        }
        return signatureMatches(matcher.group(1), rawBody, matcher.group(2));
    }

    /**
     * 署名を計算し直して突き合わせる。
     *
     * <p>
     * equals ではなく MessageDigest.isEqual を使う。前者は不一致の位置で比較を打ち切るため、
     * 応答時間の差から正しい署名を1文字ずつ絞り込める余地が残る。
     */
    private boolean signatureMatches(String timestamp, String rawBody, String received) {
        String expected = hmacSha256Hex(timestamp + "." + rawBody, webhookProperty.webhookSecret());
        return MessageDigest.isEqual(expected.getBytes(UTF_8), received.getBytes(UTF_8));
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

package com.example.settlement.payment.adapter.out.gateway;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PSPへの送信を担うゲートウェイ。
 * PspDispatchRelay がトランザクションの外側から呼ぶ(design.md §5.1)。
 */
@Component
public class PspClient {

    private final RestClient restClient;

    PspClient(PspProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // REQ-NFR-003: 接続3秒 / 読み取り5秒
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 与信をPSPへ送信する。正常終了は 202 の受理のみを意味し、決済の成否ではない。
     * dispatchEventId をそのまま Idempotency-Key として送る(REQ-PSP-003)。
     * リトライ時も同じ値を送るため、PSP側で重複と判定される(REQ-SIM-005)。
     *
     * @throws PspDispatchFailedException 接続失敗・タイムアウト・2xx以外の応答
     */
    public void authorize(UUID dispatchEventId, UUID paymentId, long amount, String currency) {
        send("/psp/authorize", dispatchEventId, paymentId, amount, currency);
    }

    /**
     * 売上確定をPSPへ送信する。与信と同じく 202 の受理のみを意味し、結果はWebhookで届く。
     */
    public void capture(UUID dispatchEventId, UUID paymentId, long amount, String currency) {
        send("/psp/capture", dispatchEventId, paymentId, amount, currency);
    }

    /** 返金をPSPへ送信する。結果はWebhookで届く。 */
    public void refund(UUID dispatchEventId, UUID paymentId, long amount, String currency) {
        send("/psp/refund", dispatchEventId, paymentId, amount, currency);
    }

    private void send(String path, UUID dispatchEventId, UUID paymentId, long amount, String currency) {
        try {
            restClient.post()
                    .uri(path)
                    .header("Idempotency-Key", dispatchEventId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PspOperationRequest(paymentId, amount, currency))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            throw new PspDispatchFailedException(dispatchEventId, e);
        }
    }

    private record PspOperationRequest(UUID paymentId, long amount, String currency) {
    }
}

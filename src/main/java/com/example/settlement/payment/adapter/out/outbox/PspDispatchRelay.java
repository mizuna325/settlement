package com.example.settlement.payment.adapter.out.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import javax.management.RuntimeErrorException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.settlement.payment.adapter.out.gateway.PspClient;
import com.example.settlement.payment.adapter.out.gateway.PspDispatchFailedException;

/**
 * Dispatch Outbox を走査し、未送信のレコードをPSPへ送る(REQ-PSP-002)。
 *
 * <p>
 * 1周の流れは3段階(design.md §5.1)。
 *
 * <pre>
 * ① 確保      トランザクション1   PspDispatchStore#claim
 * ② 送信      トランザクション外   PspClient#authorize
 * ③ 結果記録  トランザクション2   PspDispatchStore#markSent / markFailed
 * </pre>
 *
 * <p>
 * ②をトランザクションに含めないのは、応答待ちの数秒間ずっと行ロックと
 * DBコネクションを占有しないため。
 */
@Component
@ConditionalOnProperty(name = "settlement.psp.dispatch.enabled", matchIfMissing = true)
class PspDispatchRelay {

    private static final Logger log = LoggerFactory.getLogger(PspDispatchRelay.class);

    private final PspDispatchStore pspDispatchStore;
    private final PspClient pspClient;
    private final PspDispatchProperties properties;

    PspDispatchRelay(PspDispatchStore pspDispatchStore, PspClient pspClient,
            PspDispatchProperties properties) {
        this.pspDispatchStore = pspDispatchStore;
        this.pspClient = pspClient;
        this.properties = properties;
    }

    /**
     * REQ-NFR-001: 走査の間隔は設定値から注入する。
     *
     * <p>
     * fixedDelay は「前回の完了から次回の開始まで」の間隔。fixedRate と違い、
     * 1周が長引いても次の周が重ならない。
     */
    @Scheduled(fixedDelayString = "${settlement.psp.dispatch.polling-interval}")
    void relay() {
        // ① 確保(トランザクション1)
        List<PspDispatchEventEntity> claimed = pspDispatchStore.claim(
                properties.batchSize(), properties.claimTimeout());

        for (PspDispatchEventEntity event : claimed) {
            // TODO: ② 送信 + ③ 結果記録
            try {
                pspClient.authorize(event.dispatchEventId(), event.paymentId(), event.amount(), event.currency());
                pspDispatchStore.markSent(event.dispatchEventId());
            } catch (PspDispatchFailedException e) {
                recordFailure(event);
                log.warn("Failed to authorize error={}", e);
            }
        }
    }

    /**
     * 送信に失敗した1件を記録する。トランザクション2にあたる。
     *
     * <p>
     * attempts は確保時に加算済みなので、この値が「今回が何回目の送信だったか」を表す。
     * maxAttempts に達していれば打ち切り、まだ余地があれば再送を予約する(REQ-PSP-004)。
     */
    private void recordFailure(PspDispatchEventEntity event) {
        if (event.attempts() >= properties.maxAttempts()) {
            pspDispatchStore.markFailed(event.dispatchEventId());
            log.warn("試行上限に達したため送信を打ち切った dispatchEventId={} attempts={}/{}",
                    event.dispatchEventId(), event.attempts(), properties.maxAttempts());
        } else {
            pspDispatchStore.scheduleRetry(event.dispatchEventId(), Instant.now().plus(backoff(event.attempts())));
        }

    }

    /**
     * REQ-NFR-002: n回目の失敗のあと backoffBase × 2^(n-1) だけ待つ。
     * backoffBase=1s なら 1 → 2 → 4 → 8 秒。
     */
    private Duration backoff(short attempts) {
        // TODO: properties.backoffBase() を 2^(attempts-1) 倍して返す
        throw new UnsupportedOperationException("TODO: バックオフの計算(REQ-NFR-002)");
    }
}

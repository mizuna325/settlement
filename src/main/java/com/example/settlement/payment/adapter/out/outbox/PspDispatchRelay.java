package com.example.settlement.payment.adapter.out.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.settlement.payment.adapter.out.gateway.PspClient;

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
            try {
                // ② 送信(トランザクション外)
                pspClient.authorize(event.dispatchEventId(), event.paymentId(), event.amount(), event.currency());
                // ③ 結果記録(トランザクション2)
                pspDispatchStore.markSent(event.dispatchEventId());
            } catch (RuntimeException e) {
                recordFailure(event, e);
            }
        }
    }

    /**
     * 送信に失敗した1件を記録する。トランザクション2にあたる。
     *
     * <p>
     * attempts は確保時に加算済みなので、この値が「今回が何回目の送信だったか」を表す。
     * maxAttempts に達していれば打ち切り、まだ余地があれば再送を予約する(REQ-PSP-004)。
     *
     * <p>
     * ログはここだけで出す。「打ち切るのか再送するのか」を知っているのはこのメソッドで、
     * 呼び出し側でも出すと1回の失敗に対して2行出る。
     * 打ち切りは自動で回復せず人手の対応が要るため ERROR、再送予約はいずれ解消するため WARN。
     */
    private void recordFailure(PspDispatchEventEntity event, RuntimeException cause) {
        if (event.attempts() >= properties.maxAttempts()) {
            pspDispatchStore.markFailed(event.dispatchEventId());
            log.error("試行上限に達したため送信を打ち切った dispatchEventId={} attempts={}/{}",
                    event.dispatchEventId(), event.attempts(), properties.maxAttempts(), cause);
        } else {
            Instant nextAttemptAt = Instant.now().plus(backoff(event.attempts()));
            pspDispatchStore.scheduleRetry(event.dispatchEventId(), nextAttemptAt);
            log.warn("PSPへの送信に失敗した。再送を予約した dispatchEventId={} attempts={}/{} nextAttemptAt={}",
                    event.dispatchEventId(), event.attempts(), properties.maxAttempts(), nextAttemptAt, cause);
        }
    }

    /**
     * REQ-NFR-002: n回目の失敗のあと backoffBase × 2^(n-1) だけ待つ。
     * backoffBase=1s なら 1 → 2 → 4 → 8 秒。
     *
     * @param attempts 今回が何回目の送信だったか。確保時に加算済みのため 1 以上、
     *                 かつ maxAttempts 未満であることを呼び出し側が保証する
     */
    private Duration backoff(short attempts) {
        return properties.backoffBase().multipliedBy((long) Math.pow(2, attempts - 1));
    }
}

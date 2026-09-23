package com.example.settlement.payment.adapter.out.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MDC.MDCCloseable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.settlement.payment.adapter.out.gateway.PspClient;
import com.example.settlement.payment.domain.PaymentOperation;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

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
    private final DispatchTraceContext traceContext;

    PspDispatchRelay(PspDispatchStore pspDispatchStore, PspClient pspClient,
            PspDispatchProperties properties, DispatchTraceContext traceContext) {
        this.pspDispatchStore = pspDispatchStore;
        this.pspClient = pspClient;
        this.properties = properties;
        this.traceContext = traceContext;
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
            // 行ごとにトレースを復元する。バッチ単位ではない。
            // 1周で10件扱えば10回の出し入れになる(design.md §8.6)。
            Span span = traceContext.startSpan(event.traceparent(), "psp-dispatch");
            // MDCも行ごとに出し入れする。この1件の処理中に出る全行(送信成功・失敗の
            // どちらも)に識別子が載る(design.md §8.8)。
            try (Tracer.SpanInScope scope = traceContext.tracer().withSpan(span);
                    MDCCloseable dispatchEventId = MDC.putCloseable(
                            "dispatchEventId", event.dispatchEventId().toString());
                    MDCCloseable paymentId = MDC.putCloseable(
                            "paymentId", event.paymentId().toString())) {
                // ② 送信(トランザクション外)
                send(event);
                // 外部へ渡った事実はプロセスの外にしか痕跡が残らないため、ここで記録する。
                // markSent の前に出すのは、③が落ちても「送った」事実は失わないようにするため。
                log.atInfo()
                        .addKeyValue("operation", event.operation())
                        .addKeyValue("amount", event.amount())
                        .addKeyValue("currency", event.currency())
                        .addKeyValue("attempts", event.attempts())
                        .log("PSPへ送信し、受理された");
                // ③ 結果記録(トランザクション2)
                pspDispatchStore.markSent(event.dispatchEventId());
            } catch (RuntimeException e) {
                span.error(e);
                recordFailure(event, e);
            } finally {
                span.end();
            }
        }
    }

    /**
     * 操作の種別ごとに送信先を振り分ける。
     *
     * <p>
     * switch 式にしているのは、PaymentOperation に値が増えたときにコンパイルエラーで
     * 気付けるようにするため。送り先を取り違えると、PSPには届くが結果が返らない。
     */
    private void send(PspDispatchEventEntity event) {
        PaymentOperation operation = PaymentOperation.valueOf(event.operation());
        switch (operation) {
            case AUTHORIZE ->
                pspClient.authorize(event.dispatchEventId(), event.paymentId(), event.amount(), event.currency());
            case CAPTURE ->
                pspClient.capture(event.dispatchEventId(), event.paymentId(), event.amount(), event.currency());
            case REFUND ->
                pspClient.refund(event.dispatchEventId(), event.paymentId(), event.amount(), event.currency());
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
        // dispatchEventId と paymentId は呼び出し元のMDCに載っている。ここでは足さない。
        if (event.attempts() >= properties.maxAttempts()) {
            pspDispatchStore.markFailed(event.dispatchEventId());
            log.atError().setCause(cause)
                    .addKeyValue("attempts", event.attempts())
                    .addKeyValue("maxAttempts", properties.maxAttempts())
                    .log("試行上限に達したため送信を打ち切った");
        } else {
            Instant nextAttemptAt = Instant.now().plus(backoff(event.attempts()));
            pspDispatchStore.scheduleRetry(event.dispatchEventId(), nextAttemptAt);
            log.atWarn().setCause(cause)
                    .addKeyValue("attempts", event.attempts())
                    .addKeyValue("maxAttempts", properties.maxAttempts())
                    .addKeyValue("nextAttemptAt", nextAttemptAt.toString())
                    .log("PSPへの送信に失敗した。再送を予約した");
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

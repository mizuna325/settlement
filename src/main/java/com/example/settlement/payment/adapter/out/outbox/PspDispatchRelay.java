package com.example.settlement.payment.adapter.out.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.settlement.payment.adapter.out.gateway.PspClient;

/**
 * Dispatch Outbox を走査し、未送信のレコードをPSPへ送る(REQ-PSP-002)。
 *
 * <p>1周の流れは3段階(design.md §5.1)。
 *
 * <pre>
 * ① 確保      トランザクション1   PspDispatchStore#claim
 * ② 送信      トランザクション外   PspClient#authorize
 * ③ 結果記録  トランザクション2   PspDispatchStore#markSent / markFailed
 * </pre>
 *
 * <p>②をトランザクションに含めないのは、応答待ちの数秒間ずっと行ロックと
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
     * <p>fixedDelay は「前回の完了から次回の開始まで」の間隔。fixedRate と違い、
     * 1周が長引いても次の周が重ならない。
     */
    @Scheduled(fixedDelayString = "${settlement.psp.dispatch.polling-interval}")
    void relay() {
        // ① 確保
        //    var claimed = pspDispatchStore.claim(properties.batchSize(), properties.claimTimeout());

        // ② 送信 + ③ 結果記録
        //    確保した各レコードについて:
        //      - pspClient.authorize(...) を呼ぶ
        //      - 正常終了なら markSent
        //      - PspDispatchFailedException なら:
        //          attempts が maxAttempts に達していれば markFailed(REQ-PSP-004)。
        //          FAILED は終端で、以降は自動で再送されない。
        //          まだ余地があれば scheduleRetry(id, now + backoffBase × 2^(attempts-1))。
        //          待たずに行を手放し、バックオフが明けた周で再び確保される(REQ-NFR-002)。
        //
        //    ここでスリープしないこと。確保したまま待つと claimTimeout を超え、
        //    処理中の行を他インスタンスが回収して二重に送信する(design.md §5.1)。
        //
        //    送信中の例外で周全体が止まらないよう、1件ずつ捕捉すること。
        //    1件の失敗が残りの未送信レコードを巻き添えにしない。

        throw new UnsupportedOperationException("TODO: 走査・送信・結果記録(design.md §5.1)");
    }
}

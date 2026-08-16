package com.example.settlement.payment.adapter.out.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ディスパッチの走査・送信に関する設定値(design.md §設定値 / requirements.md §4)。
 *
 * @param pollingInterval 走査の間隔(REQ-NFR-001)
 * @param maxAttempts     送信の試行上限。超過で FAILED(REQ-NFR-002)
 * @param backoffBase     再送までの待ち時間の基準値。n回目の失敗で backoffBase × 2^(n-1)
 *                        だけ待つ(REQ-NFR-002)
 * @param claimTimeout    確保済みの行を回収するまでの時間(REQ-NFR-009)
 * @param batchSize       1回の走査で確保する最大件数(REQ-NFR-010)
 * @param enabled         Relay を動かすか。テストでは既定で false にする
 */
@ConfigurationProperties(prefix = "settlement.psp.dispatch")
record PspDispatchProperties(
        Duration pollingInterval,
        int maxAttempts,
        Duration backoffBase,
        Duration claimTimeout,
        int batchSize,
        boolean enabled) {
}

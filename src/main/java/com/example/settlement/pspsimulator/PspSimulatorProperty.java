package com.example.settlement.pspsimulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * シミュレータがWebhookを送る際の設定値(design.md §設定値)。
 *
 * @param webhookUrl            送信先。settlement の受信エンドポイント
 * @param webhookDelayMin       送信までの遅延の下限(REQ-SIM-002)
 * @param webhookDelayMax       送信までの遅延の上限(REQ-SIM-002)
 * @param webhookSecret         HMAC署名の共有シークレット(REQ-SIM-006)
 * @param webhookConnectTimeout 接続のタイムアウト
 * @param webhookReadTimeout    読み取りのタイムアウト。応答を待つあいだスケジューラの
 *                              スレッドを掴み続けるため、無期限にしない
 */
@ConfigurationProperties(prefix = "settlement.pspsimulator")
public record PspSimulatorProperty(String webhookUrl, Duration webhookDelayMin, Duration webhookDelayMax,
        String webhookSecret, Duration webhookConnectTimeout, Duration webhookReadTimeout,
        Duration idempotencyKeyRetention) {

}

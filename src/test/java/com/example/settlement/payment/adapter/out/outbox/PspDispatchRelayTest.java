package com.example.settlement.payment.adapter.out.outbox;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PspDispatchRelay の結合テスト。
 *
 * Relay は既定で無効(src/test/resources/application.properties)。ここでは
 * @Scheduled の発火を待たず、relay() を直接呼んで1周ぶんを検証する。
 * 走査間隔そのもの(REQ-NFR-001)はスケジューラの設定値であり、
 * 挙動として検証する対象ではない。
 */
@SpringBootTest
@Disabled("ステップ2で実装する")
class PspDispatchRelayTest {

    @Autowired
    PspDispatchRelay pspDispatchRelay;

    @Autowired
    PspDispatchStore pspDispatchStore;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    @DisplayName("REQ-PSP-002: 未送信のディスパッチは PENDING → SENDING → SENT と進む")
    void pendingDispatchIsSentToPsp() {
        // TODO: PENDING の行を1件用意し、relay() を1周させて status='SENT' になることを確認する
    }

    @Test
    @DisplayName("REQ-PSP-003: リトライ時も同一の dispatch_event_id が Idempotency-Key として送られる")
    void reusesSameIdempotencyKeyOnRetry() {
        // TODO: 同じ行を2周ぶん送信し、PSP側の payment_psp_idempotency_keys が1件のままであることを確認する
    }

    @Test
    @DisplayName("REQ-PSP-004 / REQ-NFR-002: 試行上限を超えたディスパッチは FAILED になり以降拾われない")
    void exceedingMaxAttemptsBecomesFailed() {
        // TODO: PSPを到達不能にした状態で maxAttempts 回まわし、status='FAILED' を確認する
        //       base-url を死んだポートに向けるのが簡単
    }

    @Test
    @DisplayName("REQ-NFR-009: claimTimeout を過ぎた SENDING の行は再び走査対象になる")
    void staleSendingRowIsReclaimed() {
        // TODO: claimed_at を claimTimeout より前に設定した SENDING の行を用意し、
        //       relay() が拾って SENT まで進めることを確認する
        //       あわせて WARN ログが出ること(design.md §5.1)
    }

    @Test
    @DisplayName("REQ-PSP-001: 確保と送信は別トランザクションで行われる")
    void claimAndSendAreSeparateTransactions() {
        // TODO: 送信が失敗しても確保(status='SENDING', attempts加算)が巻き戻らないことを確認する
        //       これが崩れると、HTTP応答待ちの間ずっと行ロックを保持する構造になる
    }
}

package com.example.settlement.payment.adapter.out.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link PspDispatchRelay} を定期的に起動する(REQ-PSP-002)。
 *
 * <p>
 * 走査の中身は持たず、起動の契機だけを担う。Relay 本体と分けているのは、
 * 「Beanが存在するか」と「自動で走るか」を別々に決められるようにするため。
 *
 * <p>
 * 以前は {@code @ConditionalOnProperty} と {@code @Scheduled} の両方を Relay に
 * 付けていた。この形だと、走査を手で呼びたいテストが enabled=true にするしかなく
 * (false ではBeanごと消えるため)、スケジューラも一緒に動いてしまう。
 * {@code @Scheduled(fixedDelay)} は<strong>初回をコンテキスト起動直後に実行する</strong>ので、
 * polling-interval をどれだけ長くしても、その1回がテスト本体と競合した。
 * 実際に「確保したはずの行が確保されていない」という形で表面化している(design.md §9.1)。
 *
 * <p>
 * 現在は enabled=false で<strong>このクラスだけが消える</strong>。Relay のBeanは残るので、
 * テストは注入して {@code relay()} を直接呼べる。裏で走るものは何も無い。
 */
@Component
@ConditionalOnProperty(name = "settlement.psp.dispatch.enabled", matchIfMissing = true)
class PspDispatchScheduler {

    private final PspDispatchRelay pspDispatchRelay;

    PspDispatchScheduler(PspDispatchRelay pspDispatchRelay) {
        this.pspDispatchRelay = pspDispatchRelay;
    }

    /**
     * REQ-NFR-001: 走査の間隔は設定値から注入する。
     *
     * <p>
     * fixedDelay は「前回の完了から次回の開始まで」の間隔。fixedRate と違い、
     * 1周が長引いても次の周が重ならない。
     */
    @Scheduled(fixedDelayString = "${settlement.psp.dispatch.polling-interval}")
    void trigger() {
        pspDispatchRelay.relay();
    }
}

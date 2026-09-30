package com.example.settlement.payment.adapter.out.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ディスパッチレコードに対するトランザクション付きの操作(design.md §5.1)。
 *
 * PspDispatchRelay とは別クラスにしている。@Transactional はプロキシ経由でしか効かず、
 * Relay の走査メソッドから同一クラスのメソッドを呼ぶと境界が張られないため。
 *
 * 状態名は SQL に直接書かず PspDispatchStatus から渡す(例: :pending に
 * PspDispatchStatus.PENDING.name())。列挙子を変えたときに文字列だけが取り残されるのを防ぐ。
 * TIMESTAMPTZ の列は OffsetDateTime で渡し、OffsetDateTime で受け取る。
 * pgjdbc が Instant を直接扱えないため。
 */
@Component
class PspDispatchStore {

    private static final Logger log = LoggerFactory.getLogger(PspDispatchStore.class);

    /**
     * 確保クエリ。CTE で対象行をロックし、同じ文で SENDING へ更新して確保した行を返す。
     * SELECT と UPDATE を2文に分けても良いが、1文なら往復が1回で済み、
     * 2文の間に別の処理が挟まる余地も無くなる。
     *
     * <p>
     * {@code FOR UPDATE} は CTE 側の SELECT に付ける(UPDATE 本体には付けられない)。
     * {@code RETURNING} があるので、実行は {@code .update()} ではなく {@code .query()} になる。
     */
    private static final String CLAIM_SQL = """
            WITH claimed AS (
                SELECT dispatch_event_id,
                       status AS previous_status,
                       claimed_at AS previous_claimed_at
                  FROM payment_psp_dispatch_events
                 WHERE (status = :pending AND next_attempt_at <= :now)
                        OR
                       (status = :sending AND claimed_at < :staleBefore)
                 ORDER BY created_at
                 LIMIT :batchSize
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE payment_psp_dispatch_events d
               SET status = :sending, claimed_at = :now, attempts = d.attempts + 1
              FROM claimed c
             WHERE d.dispatch_event_id = c.dispatch_event_id
            RETURNING d.dispatch_event_id, d.payment_id, d.operation, d.amount, d.currency,
                      d.status, d.attempts, d.claimed_at, d.next_attempt_at, d.created_at,
                      d.traceparent,
                      c.previous_status, c.previous_claimed_at
            """;

    private final JdbcClient jdbcClient;

    PspDispatchStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 送信対象を確保する。トランザクション1にあたる。
     *
     * <p>
     * 対象は次の2種類。
     * <ul>
     * <li>{@code status = 'PENDING'} かつ {@code next_attempt_at} が現在時刻以前の行
     * (未送信行と、失敗してバックオフが明けた行。REQ-NFR-002)</li>
     * <li>{@code status = 'SENDING'} かつ {@code claimed_at} が claimTimeout を過ぎた行
     * (回収対象。送信直前にプロセスが停止して取り残された行。REQ-NFR-009)</li>
     * </ul>
     *
     * <p>
     * {@code ORDER BY created_at} で古い順に、{@code LIMIT} 件を
     * {@code FOR UPDATE SKIP LOCKED} で確保する。SKIP LOCKED により、複数インスタンスが
     * 互いを待たずに重複しない集合を処理できる。
     *
     * <p>
     * 確保した行は {@code status='SENDING'}, {@code claimed_at=now()},
     * {@code attempts=attempts+1} に更新する。attempts を送信失敗時ではなく確保時に
     * 加算するのは、送信前にプロセスが落ちてもカウントを進めるため。
     *
     * <p>
     * 回収した行はここで WARN に残す(design.md §5.1)。更新前の状態を知っているのは
     * このクエリだけであり、呼び出し側へ運ぶには戻り値の型を増やすことになるため。
     */
    @Transactional
    List<PspDispatchEventEntity> claim(int batchSize, Duration claimTimeout) {
        // 1回の走査で時刻がぶれないよう、now は1つだけ作って両方の条件に使う。
        // interval はパラメータ化できないため、claimTimeout の引き算は Java 側で行う。
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime staleBefore = now.minus(claimTimeout);

        List<ClaimedRow> claimed = jdbcClient.sql(CLAIM_SQL)
                .param("pending", PspDispatchStatus.PENDING.name())
                .param("sending", PspDispatchStatus.SENDING.name())
                .param("now", now)
                .param("staleBefore", staleBefore)
                .param("batchSize", batchSize)
                .query(PspDispatchStore::toClaimedRow)
                .list();

        // 確保はRelayのループの手前で走るため、MDCにはまだ識別子が載っていない。
        // この2行だけは自分で addKeyValue する。
        claimed.stream()
                .filter(ClaimedRow::reclaimed)
                .forEach(row -> log.atWarn()
                        .addKeyValue("dispatchEventId", row.event().dispatchEventId().toString())
                        .addKeyValue("paymentId", row.event().paymentId().toString())
                        .addKeyValue("attempts", row.event().attempts())
                        .addKeyValue("previousClaimedAt", String.valueOf(row.previousClaimedAt()))
                        .log("reclaimed a dispatch left behind while claimed"));

        return claimed.stream().map(ClaimedRow::event).toList();
    }

    /**
     * 送信がPSPに受理された(202)。トランザクション2にあたる。
     *
     * <p>
     * {@code status='SENDING'} を条件に含めること。理由は {@link #warnIfNotClaimed}。
     */
    @Transactional
    void markSent(UUID dispatchEventId) {
        int updated = jdbcClient.sql("""
                UPDATE payment_psp_dispatch_events
                SET status = :sent
                WHERE dispatch_event_id = :dispatchEventId AND status = :sending
                """)
                .param("sent", PspDispatchStatus.SENT.name())
                .param("sending", PspDispatchStatus.SENDING.name())
                .param("dispatchEventId", dispatchEventId)
                .update();

        warnIfNotClaimed(updated, dispatchEventId, PspDispatchStatus.SENT);
    }

    /**
     * 送信に失敗した。再送できる状態に戻す。トランザクション2にあたる。
     *
     * <p>
     * {@code status='PENDING'} に戻し、{@code next_attempt_at} に nextAttemptAt を入れる。
     * 確保したまま待つのではなく行を手放すため、Relay のスレッドを塞がず、
     * 待機の状態がプロセスの再起動をまたいでも残る。
     *
     * <p>
     * バックオフの計算(REQ-NFR-002)は呼び出し側で行い、ここには結果の時刻だけを渡す。
     * 待ち時間の基準値は設定値から注入するため、SQLに式を埋め込むとテストで短縮できなくなる。
     *
     * @param nextAttemptAt 次に確保してよい時刻。{@link #claim} がこの時刻を過ぎるまで拾わない
     */
    @Transactional
    void scheduleRetry(UUID dispatchEventId, Instant nextAttemptAt) {
        int updated = jdbcClient.sql("""
                UPDATE payment_psp_dispatch_events
                SET status = :pending, next_attempt_at = :nextAttemptAt
                WHERE dispatch_event_id = :dispatchEventId AND status = :sending
                """)
                .param("pending", PspDispatchStatus.PENDING.name())
                .param("sending", PspDispatchStatus.SENDING.name())
                .param("dispatchEventId", dispatchEventId)
                .param("nextAttemptAt", OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
                .update();
        warnIfNotClaimed(updated, dispatchEventId, PspDispatchStatus.PENDING);
    }

    /**
     * 試行上限に達したため送信を打ち切る(REQ-PSP-004 / REQ-NFR-002)。トランザクション2にあたる。
     *
     * <p>
     * FAILED は終端。走査クエリと部分索引のどちらも FAILED を対象外にしているため、
     * 以降この行が自動で再送されることはなく、人手で対応する。
     * 1回ぶんの送信失敗をここで記録してはならない。再送の余地がある行まで打ち切られる。
     */
    @Transactional
    void markFailed(UUID dispatchEventId) {
        int updated = jdbcClient.sql("""
                UPDATE payment_psp_dispatch_events
                SET status = :failed
                WHERE dispatch_event_id = :dispatchEventId AND status = :sending
                """)
                .param("failed", PspDispatchStatus.FAILED.name())
                .param("sending", PspDispatchStatus.SENDING.name())
                .param("dispatchEventId", dispatchEventId)
                .update();

        warnIfNotClaimed(updated, dispatchEventId, PspDispatchStatus.FAILED);
    }

    /**
     * 結果を記録する更新はいずれも {@code status='SENDING'} を条件に含める。claimTimeout を
     * 過ぎて他インスタンスに確保し直された行を上書きしないため。1件も更新されなければ
     * それが起きているので、黙って捨てずに残す。
     */
    private void warnIfNotClaimed(int updated, UUID dispatchEventId, PspDispatchStatus status) {
        if (updated == 0) {
            log.atWarn()
                    .addKeyValue("dispatchEventId", dispatchEventId.toString())
                    .addKeyValue("targetStatus", status.name())
                    .log("tried to update the status of a dispatch that is not claimed");
        }
    }

    /** 確保した行と、更新前の状態。更新前の状態は回収の検知にのみ使う。 */
    private record ClaimedRow(PspDispatchEventEntity event, String previousStatus, Instant previousClaimedAt) {

        boolean reclaimed() {
            return PspDispatchStatus.SENDING.name().equals(previousStatus);
        }
    }

    /** CLAIM_SQL の RETURNING 1行を ClaimedRow へ移す。 */
    private static ClaimedRow toClaimedRow(ResultSet rs, int rowNum) throws SQLException {
        PspDispatchEventEntity event = new PspDispatchEventEntity(
                rs.getObject("dispatch_event_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("operation"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getShort("attempts"),
                instantOf(rs, "claimed_at"),
                instantOf(rs, "next_attempt_at"),
                instantOf(rs, "created_at"),
                rs.getString("traceparent"));

        return new ClaimedRow(event, rs.getString("previous_status"), instantOf(rs, "previous_claimed_at"));
    }

    /**
     * TIMESTAMPTZ を Instant として取り出す。pgjdbc は Instant を直接返せないため、
     * OffsetDateTime を経由する。claimed_at は未確保なら NULL になりうる。
     */
    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

# ADR-0002: 冪等性キーストアをPSP側に置く

## Status

Accepted

## Context

design.md §2は`PspIdempotencyKeyPort`(`payment.application.port.out`)と`PspIdempotencyKeyJdbcStore`(`payment.adapter.out.idempotency`)を置き、plan.mdはステップ2の「送信側の冪等性」として`payment_psp_idempotency_keys`を挙げていた。

しかし送信側の冪等性は、`dispatch_event_id`を`Idempotency-Key`として送り、リトライ時も同じ値を使う(REQ-PSP-003)ことだけで成立する。settlement側にキーを記録する必要はない。一方REQ-SIM-005は、PSPが受付済みのキーを判定して重複処理を避けることを求めており、その判定には受付履歴の保持が要る。REQ-NFR-006の保持期間24時間も、PSPが同一キーを覚えている期間を指す。

加えて、ステップ2以降で予定している障害注入(送信直前にプロセスを停止させ、回収が働くことを示す)では、再送されたリクエストがPSP側で重複と判定される必要がある。キーをメモリに保持すると、settlementと同一プロセスで動くPSPスタブの状態も同時に失われ、「この回収処理は冪等性キーの存在を前提として初めて成立する」(design.md §5.1)を実演できない。

## Decision

`payment_psp_idempotency_keys`をPSP側の状態として扱い、`pspsimulator`から読み書きする。settlement側には冪等性キーのポートもアダプタも設けない。

## Consequences

- `PspIdempotencyKeyPort`と`PspIdempotencyKeyJdbcStore`を設計・実装対象から削除した。design.md §2の該当記述を除いた。
- `pspsimulator`がsettlementと同じスキーマのテーブルを読み書きする。境界づけられたコンテキストとしては越境だが、PSPを別サービスとして分離していない演習の制約による。実サービスへ置き換える際はこのテーブルごと不要になる。
- 重複排除は主キー制約と`ON CONFLICT DO NOTHING`で行う。ステップ3の`payment_webhook_events`(REQ-PSP-006)も同じ形を取る。
- REQ-NFR-006の削除処理は`PspIdempotencyKeyStore.deleteExpired`として実装済み。定期実行はステップ2で`@EnableScheduling`を導入する際に接続する。

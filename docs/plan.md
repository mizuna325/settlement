# 5日間の実装プラン

> 本書は**いつ作るか**(実装の順序と各日の完了条件)を扱う。
> - なぜそうなのか(業務言語・業務ルール): [ubiquitous-language.md](./ubiquitous-language.md)
> - 何を満たすべきか(要件ID・状態遷移・非機能要件): [requirements.md](./requirements.md)
> - どう作るか(アーキテクチャ・実装方式): [design.md](./design.md)

実装は人手で行うため、タスクはDay単位の粒度に留める。各Dayの完了条件を満たしているかは、対応する要件IDのテストが通ることで判定する。

| Day | ゴール | やること | 完了条件 (DoD) |
|---|---|---|---|
| Day 1 | パッケージ再編・ArchUnit導入・ドメインモデリング | `order`/`payment`/`shared`/`pspsimulator`にパッケージ再編 / ArchUnitテスト([design.md §7](./design.md))を追加 / `shared.Money`実装 / Order集約実装(`OrderStatus` 7値) / Payment集約実装(`PaymentStatus` 9値 + Authorization/Capture/Refundエンティティ) / Flywayで全テーブル作成 | `mvn test`でArchUnitテストが通る。REQ-PAY-005/006/007/008/010の不変条件違反がユニットテストで正しく弾かれる |
| Day 2 | 注文作成と与信ディスパッチ(自社側のみ) | `POST /orders`実装。1つのローカルトランザクションで`Order.create()` + `AuthorizePaymentUseCase.authorize()`(Payment生成 + PspDispatchEvent insert)を実行 | REQ-ORD-001/REQ-PAY-001/REQ-PSP-001。注文作成でOrderが`PENDING`、Paymentが`AUTHORIZING`で保存され、Dispatch Outboxにレコードが積まれる |
| Day 3 | PSP連携とWebhook受信(与信の完結 + 売上確定の連鎖) | `pspsimulator`実装(202即答 + `WebhookDispatcher`で遅延送信、金額ルールで可否判定) / `PspDispatchRelay`実装 / `PspWebhookController`実装(署名検証・`eventId`冪等チェック) / `PaymentOutcomePort`と`PaymentOutcomeAdapter`実装 / 与信成功と同一トランザクション内で売上確定をディスパッチ | REQ-ORD-002/003、REQ-PAY-002/003/004、REQ-PSP-002〜006。与信成功でOrderが`CONFIRMED`になり売上確定がディスパッチされる。与信拒否で`CANCELLED`。重複Webhookが二重処理されない |
| Day 4 | 売上確定の結果反映・返金 | 売上確定WebhookでOrderを`SETTLED`にする実装 / 失敗時に`SETTLEMENT_FAILED`にする実装 / 返金の入口(`POST /orders/{id}/refunds`)と`RefundPaymentUseCase`実装 | REQ-ORD-004/005/006/009、REQ-PAY-009/011。売上確定成功で`SETTLED`、失敗で`SETTLEMENT_FAILED`。部分/全額返金で`PARTIALLY_REFUNDED`/`REFUNDED` |
| Day 5 | 結合テストと可観測性 | 正常系(与信→売上確定)/与信拒否/売上確定失敗/部分返金/重複Webhookの5シナリオをE2E化 / 相関IDをログ・Dispatch Outbox・Webhookペイロードに伝搬 / Actuatorでヘルス確認 / README整備 | REQ-NFR-008。5シナリオがテストで通り、相関IDで1注文分の一連の処理を追跡できる |

## スコープ外

- **CIパイプラインの構築**。ArchUnitと各テストはローカルでの`mvn test`で実行する([design.md §7](./design.md))

## 5日を超えて拡張する場合

- CIの導入。ArchUnitによる依存関係の検証を確実に実行させる
- 与信取消(Void)の追加。出荷等の業務トリガーを挟む設計に変える場合は必要になる([ADR-0001](./adr/0001-exclude-void-from-scope.md))
- 部分売上確定への対応(現状は与信額の全額を1回で確定するのみ)
- `order`/`payment`/`shared`を別Mavenモジュールへ分割し、依存をコンパイル時に強制する
- `PspDispatchRelay`をKafka Producerへ、`PaymentOutcomePort`をKafka Consumerへ置き換えて物理的にサービス分割する
- `pspsimulator`を実際の決済代行会社のサンドボックス環境へ置き換える

# 実装プラン

> 本書は**いつ作るか**(実装の順序と各ステップの完了条件)を扱う。
> - なぜそうなのか(業務言語・業務ルール): [ubiquitous-language.md](./ubiquitous-language.md)
> - 何を満たすべきか(要件ID・状態遷移・非機能要件): [requirements.md](./requirements.md)
> - どう作るか(アーキテクチャ・実装方式): [design.md](./design.md)

**ユースケース単位で縦に切る。** 各ステップは端から端まで動作する状態で完了させ、その時点で何が動くかをDoDとする。層ごとにまとめて作らない。

この切り方を採る理由は次の3点。

- 呼び出し側が確定してから作るため、使われないインターフェースを作らずに済む
- 最も不確実な非同期処理(ステップ2〜3)に早い段階で触れられる
- 動くものが早く出るため、設計の誤りを早く発見できる

ドメインモデルはステップをまたいで育てる。`Payment`集約は与信(ステップ1)→売上確定(ステップ4)→返金(ステップ5)の順に、必要なメソッドと子エンティティが増える。Flywayのマイグレーションも同様に、各ステップで必要なテーブルを追加していく。

完了条件を満たしているかは、対応する要件IDのテストが通ることで判定する。**[requirements.md](./requirements.md)の全要件IDがいずれかのステップのDoDに現れる**ようにしており、どのステップにも属さない要件は存在しない。

| ステップ | ゴール | やること | 完了条件 (DoD) |
|---|---|---|---|
| 1 | 注文を作成し、与信をディスパッチする | `Payment`集約を与信に必要な範囲で実装(`PaymentStatus` 9値 + `Authorization`) / 永続化専用モデルとマッパー(`order`・`payment`) / リポジトリ実装 / `CreateOrderUseCase`と`AuthorizePaymentUseCase` / `POST /orders` / ArchUnitテスト([design.md §7](./design.md)) / Flyway(`orders`, `order_lines`, `payments`, `payment_authorizations`, `payment_psp_dispatch_events`) | REQ-ORD-001、REQ-ORD-008、REQ-PAY-001、REQ-PAY-012、REQ-PSP-001。`POST /orders`でOrderが`PENDING`、Paymentが`AUTHORIZING`で保存され、Dispatch Outboxに1件積まれる。不正な遷移が集約で弾かれる。`mvn test`でArchUnitテストが通る |
| 2 | ディスパッチをPSPへ送信する | `pspsimulator`の`/authorize`(202即答のみ、冪等性キーによる重複排除) / `PspDispatchRelay`(走査・確保・送信・リトライ・回収) / `PspClient` / 送信側の冪等性(`Idempotency-Key`の再利用) / Flyway(`payment_psp_idempotency_keys`。[ADR-0002](./adr/0002-idempotency-key-store-on-psp-side.md)によりPSP側のストア) | REQ-PSP-002、REQ-PSP-003、REQ-PSP-004、REQ-NFR-001、REQ-NFR-002、REQ-NFR-003、REQ-NFR-006、REQ-NFR-009、REQ-SIM-001、REQ-SIM-005。`PENDING`→`SENDING`→`SENT`と進む。試行上限超過で`DEAD`。`SENDING`のまま放置された行が回収される。同一の冪等性キーでPSPが二重処理しない |
| 3 | 与信結果を受けて注文を確定・取消する | `WebhookDispatcher`(遅延送信 + HMAC署名 + 手動再送) / `PspWebhookController`(署名検証・`eventId`冪等チェック) / 受信側の冪等性 / `PaymentOutcomePort`と`PaymentOutcomeAdapter` / Flyway(`payment_webhook_events`) | REQ-ORD-002、REQ-ORD-003、REQ-PAY-002、REQ-PAY-003、REQ-PSP-005、REQ-PSP-006、REQ-PSP-007、REQ-PSP-008、REQ-NFR-004、REQ-NFR-005、REQ-NFR-007、REQ-SIM-002、REQ-SIM-003、REQ-SIM-006、REQ-SIM-007。与信成功でOrderが`CONFIRMED`、拒否で`CANCELLED`。重複Webhookが二重処理されない。署名不正で`401`。適用できないWebhookは`200`とWARNログ。**ここで一連のサイクルが端から端まで通る** |
| 4 | 売上確定 | `Capture`エンティティ / `Payment.capture()`と不変条件 / `CapturePaymentService`(与信成功と同一トランザクション内で起動) / `pspsimulator`の`/capture` / Flyway(`payment_captures`) | REQ-PAY-004、REQ-PAY-005、REQ-PAY-006、REQ-PAY-010、REQ-ORD-004、REQ-ORD-005、REQ-SIM-004。売上確定成功で`SETTLED`、失敗で`SETTLEMENT_FAILED`。与信額超過・有効期限切れ・`PENDING`の子の重複が集約で弾かれる |
| 5 | 返金 | `Refund`エンティティ / `Payment.refund()`と不変条件 / `RefundPaymentUseCase` / `POST /orders/{id}/refunds` / `pspsimulator`の`/refund` / Flyway(`payment_refunds`) | REQ-ORD-006、REQ-ORD-009、REQ-PAY-007、REQ-PAY-008、REQ-PAY-009、REQ-PAY-011。部分/全額返金で`PARTIALLY_REFUNDED`/`REFUNDED`。売上確定未完了と累計超過が集約で弾かれる。返金失敗で直前の状態へ戻る |
| 6 | 照会・結合テスト・可観測性 | 注文と決済の照会API / 正常系・与信拒否・売上確定失敗・部分返金・重複Webhookの5シナリオをE2E化 / 相関IDをログ・Dispatch Outbox・Webhookペイロードに伝搬 / Actuatorでヘルス確認 / README整備 | REQ-ORD-007、REQ-PAY-013、REQ-NFR-008。5シナリオがテストで通り、相関IDで1注文分の一連の処理を追跡できる |

ステップ2と3が本設計で最も不確実な部分であり、ここを早く通すことがリスク低減の中心になる。ステップ4と5はステップ1〜3で確立した「業務データの更新とディスパッチを同一トランザクションで行い、Relayが送信し、Webhookで結果を確定させる」というサイクルの2周目・3周目にあたるため、構造は変わらない。

## スコープ外

- **CIパイプラインの構築**。ArchUnitと各テストはローカルでの`mvn test`で実行する([design.md §7](./design.md))

## 本プランを超えて拡張する場合

- CIの導入。ArchUnitによる依存関係の検証を確実に実行させる
- 与信取消(Void)の追加。出荷等の業務トリガーを挟む設計に変える場合は必要になる([ADR-0001](./adr/0001-exclude-void-from-scope.md))
- 部分売上確定への対応(現状は与信額の全額を1回で確定するのみ)
- `order`/`payment`/`shared`を別Mavenモジュールへ分割し、依存をコンパイル時に強制する
- `PspDispatchRelay`をKafka Producerへ、`PaymentOutcomePort`をKafka Consumerへ置き換えて物理的にサービス分割する
- `pspsimulator`を実際の決済代行会社のサンドボックス環境へ置き換える

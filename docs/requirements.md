# 決済ドメイン 要件定義

本書は「システムが何を満たすべきか」を、検証可能な単位に分解して定義する。

| ドキュメント | 扱う内容 |
|---|---|
| [ubiquitous-language.md](./ubiquitous-language.md) | なぜそうなのか(業務言語・業務ルール) |
| **本書 (requirements.md)** | **何を満たすべきか(要件・状態遷移・具体値)** |
| [design.md](./design.md) | どう作るか(アーキテクチャ・パッケージ構成・実装方式) |
| [plan.md](./plan.md) | いつ作るか(実装の順序と完了条件) |
| [adr/](./adr/) | 個別の設計判断とその経緯 |

---

## 1. 要件IDとトレーサビリティ

### ID体系

| 接頭辞 | 対象 |
|---|---|
| `REQ-ORD` | 注文コンテキストの機能要件 |
| `REQ-PAY` | 決済コンテキストの機能要件 |
| `REQ-PSP` | 外部PSPとの連携(ディスパッチ・Webhook・冪等性) |
| `REQ-NFR` | 非機能要件(タイムアウト・リトライ等の具体値) |
| `REQ-SIM` | 演習用PSPシミュレータの仕様 |

### アンカーの規約

要件は「業務根拠 → 要件 → テスト → 実装」で連結する。実装時は**テストのメソッド名または`@DisplayName`に要件IDを含める**ことでこの鎖を成立させる。

```java
@Test
@DisplayName("REQ-PAY-005: 売上確定額が与信額を超える場合は拒否される")
void captureExceedingAuthorizedAmountIsRejected() { ... }
```

これにより、要件IDでgrepすれば「その要件がどのテストで担保されているか」を追跡でき、テストが存在しない要件は未実装として検出できる。タスクリストは別途管理しない(実装は人手で行うため、[plan.md](./plan.md)のステップの粒度で足りる)。

---

## 2. 状態機械

### 2.1 注文 (Order) の状態

| 状態 | 意味 |
|---|---|
| `PENDING` | 注文を受け付けたが、与信結果を待っている |
| `CONFIRMED` | 与信が成功し、注文が確定した |
| `CANCELLED` | 与信が拒否され、注文が成立しなかった |
| `SETTLED` | 売上確定が完了し、代金を実際に受け取った |
| `SETTLEMENT_FAILED` | 与信は成功したが売上確定に失敗した(要人手対応) |
| `PARTIALLY_REFUNDED` | 代金の一部を返金した |
| `REFUNDED` | 代金の全額を返金した |

```
PENDING ──与信成功──→ CONFIRMED ──売上確定成功──→ SETTLED ──一部返金──→ PARTIALLY_REFUNDED
   │                      │                          │                        │
   │                      │                          │                        └─全額到達─→ REFUNDED
   │                      │                          └─全額返金────────────────────────→ REFUNDED
   │                      └──売上確定失敗──→ SETTLEMENT_FAILED
   └──与信拒否──→ CANCELLED
```

`CANCELLED` / `SETTLEMENT_FAILED` / `REFUNDED` は終端状態。

### 2.2 決済 (Payment) の状態

`Payment`集約は、子エンティティ(Authorization / Capture / Refund)の状態と整合する形で、自身も明示的な状態を保持する。照会や滞留検知をこの列だけで行えるようにするため、導出値ではなく永続化された属性とする。

| 状態 | 意味 |
|---|---|
| `AUTHORIZING` | 与信をPSPへ依頼し、結果を待っている |
| `AUTHORIZED` | 与信が承認された |
| `AUTH_DECLINED` | 与信が拒否された |
| `CAPTURING` | 売上確定をPSPへ依頼し、結果を待っている |
| `CAPTURED` | 売上確定が完了した |
| `CAPTURE_FAILED` | 売上確定に失敗した |
| `REFUNDING` | 返金をPSPへ依頼し、結果を待っている |
| `PARTIALLY_REFUNDED` | 返金が完了したが、累計返金額が売上確定額に達していない |
| `REFUNDED` | 累計返金額が売上確定額に達した |

```
AUTHORIZING ──承認──→ AUTHORIZED ──即時──→ CAPTURING ──成功──→ CAPTURED
     │                                          │                   │
     │                                          └─失敗─→ CAPTURE_FAILED
     └──拒否──→ AUTH_DECLINED                                       │
                                                                    ↓
                        REFUNDED ←─累計到達─ REFUNDING ←──返金要求───┤
                                       │  ↑                         │
                    PARTIALLY_REFUNDED ←┘  └──追加の返金要求──────────┘
                                                    (失敗時は直前の状態へ復帰)
```

`AUTHORIZED`は、与信成功と同一トランザクション内で直ちに売上確定へ進む(REQ-PAY-004)ため、通常は永続化された状態として観測されない通過点である。

### 2.3 子エンティティの状態

| エンティティ | 状態 |
|---|---|
| `Authorization` | `PENDING` → `AUTHORIZED` / `DECLINED` |
| `Capture` | `PENDING` → `CAPTURED` / `FAILED` |
| `Refund` | `PENDING` → `REFUNDED` / `FAILED` |

`PENDING`はいずれも「PSPへ依頼済みで、Webhookによる確定を待っている」ことを表す。

---

## 3. 機能要件

### 3.1 注文コンテキスト (REQ-ORD)

| ID | 要件 | 根拠・備考 |
|---|---|---|
| REQ-ORD-001 | WHEN 顧客が商品・数量・金額を指定して注文を要求したとき、THE SYSTEM SHALL 注文を`PENDING`で永続化し、同一トランザクション内で与信を開始する | |
| REQ-ORD-002 | WHEN 与信成功の通知を受けたとき、THE SYSTEM SHALL 対応する注文を`CONFIRMED`へ遷移させる | |
| REQ-ORD-003 | WHEN 与信拒否の通知を受けたとき、THE SYSTEM SHALL 対応する注文を`CANCELLED`へ遷移させる | |
| REQ-ORD-004 | WHEN 売上確定完了の通知を受けたとき、THE SYSTEM SHALL 対応する注文を`SETTLED`へ遷移させる | |
| REQ-ORD-005 | WHEN 売上確定失敗の通知を受けたとき、THE SYSTEM SHALL 対応する注文を`SETTLEMENT_FAILED`へ遷移させる | 与信取消(Void)はスコープ外のため、残存する与信枠は自然失効に委ねる([ADR-0001](./adr/0001-exclude-void-from-scope.md)) |
| REQ-ORD-006 | WHEN 返金完了の通知を受けたとき、THE SYSTEM SHALL 累計返金額が売上確定額に達していれば`REFUNDED`、未満であれば`PARTIALLY_REFUNDED`へ遷移させる | |
| REQ-ORD-007 | THE SYSTEM SHALL 注文IDを指定した照会に対し、注文の現在状態と金額を返す | |
| REQ-ORD-008 | IF §2.1で定義されていない状態遷移が要求された場合、THEN THE SYSTEM SHALL 当該要求を拒否し、注文の状態を変更しない | 遷移の妥当性は`Order`集約自身が判定する |
| REQ-ORD-009 | WHEN 注文に対する返金が要求されたとき、THE SYSTEM SHALL 返金額(全額または一部)と理由を受け取り、決済コンテキストへ返金を要求する | 返金処理の起点。注文が`SETTLED`または`PARTIALLY_REFUNDED`でない場合はREQ-PAY-007により拒否される |

### 3.2 決済コンテキスト (REQ-PAY)

| ID | 要件 | 根拠・備考 |
|---|---|---|
| REQ-PAY-001 | WHEN 注文から与信が要求されたとき、THE SYSTEM SHALL `Payment`を`AUTHORIZING`で、`Authorization`を`PENDING`で生成する | |
| REQ-PAY-002 | WHEN 与信成功のWebhookを受信したとき、THE SYSTEM SHALL `Authorization`を`AUTHORIZED`とし、PSP側の参照IDと有効期限を記録する | |
| REQ-PAY-003 | WHEN 与信拒否のWebhookを受信したとき、THE SYSTEM SHALL `Authorization`を`DECLINED`、`Payment`を`AUTH_DECLINED`とする | |
| REQ-PAY-004 | WHEN 与信が成功したとき、THE SYSTEM SHALL 同一トランザクション内で売上確定を開始する | 出荷等の業務トリガーは待たない([ADR-0001](./adr/0001-exclude-void-from-scope.md)) |
| REQ-PAY-005 | IF 売上確定の要求金額が対応する与信金額を超える場合、THEN THE SYSTEM SHALL 当該要求を拒否し、集約の状態を変更しない | UL §7「売上確定は与信した金額を超えられない」 |
| REQ-PAY-006 | IF 与信の有効期限を過ぎている場合、THEN THE SYSTEM SHALL 売上確定の要求を拒否する | UL §7「与信には有効期限がある」 |
| REQ-PAY-007 | IF 対応する売上確定が完了していない場合、THEN THE SYSTEM SHALL 返金の要求を拒否する | UL §7「返金は売上確定した金額を超えられない」 |
| REQ-PAY-008 | IF 既存の返金額の累計に今回の要求額を加えた値が売上確定額を超える場合、THEN THE SYSTEM SHALL 当該要求を拒否する | 同上。部分返金の積み上げを考慮する |
| REQ-PAY-009 | WHEN 返金完了のWebhookを受信したとき、THE SYSTEM SHALL 累計返金額が売上確定額に達していれば`REFUNDED`、未満であれば`PARTIALLY_REFUNDED`とする | |
| REQ-PAY-010 | IF 同一種別の操作を表す`PENDING`の子エンティティが既に存在する場合、THEN THE SYSTEM SHALL 新規の要求を拒否する | 二重ディスパッチの防止 |
| REQ-PAY-011 | WHEN 返金失敗のWebhookを受信したとき、THE SYSTEM SHALL 当該`Refund`を`FAILED`とし、`Payment`を直前の状態(`CAPTURED`または`PARTIALLY_REFUNDED`)へ戻す | |
| REQ-PAY-012 | THE SYSTEM SHALL §3.2の各不変条件を`Payment`集約のメソッド内で判定する | application層のif文で代替してはならない |
| REQ-PAY-013 | THE SYSTEM SHALL 決済IDを指定した照会に対し、決済の現在状態、与信額、売上確定額、返金累計額を返す | |

### 3.3 PSP連携 (REQ-PSP)

| ID | 要件 | 根拠・備考 |
|---|---|---|
| REQ-PSP-001 | WHEN PSPへの操作(与信・売上確定・返金)が必要になったとき、THE SYSTEM SHALL 業務データの更新と**同一トランザクション内で**ディスパッチレコードを永続化する | Transactional Outboxの中核。ここが分離すると整合性が壊れる |
| REQ-PSP-002 | THE SYSTEM SHALL ディスパッチレコードを定期的に走査し、未送信のものをPSPへ非同期に送信する | HTTP呼び出しをDBトランザクション内で行わないため |
| REQ-PSP-003 | THE SYSTEM SHALL PSPへの各リクエストに、ディスパッチレコードのIDを冪等性キーとして付与する。リトライ時も同一の値を用いる | UL §4「冪等性キー」 |
| REQ-PSP-004 | IF PSPへの送信が失敗した場合、THEN THE SYSTEM SHALL REQ-NFR-002の回数まで再送し、超過した場合は当該レコードを`FAILED`として送信を停止する | 無限リトライを避ける |
| REQ-PSP-005 | IF Webhookの署名検証に失敗した場合、THEN THE SYSTEM SHALL `401 Unauthorized`を返し、いかなる状態変更も行わない | なりすまし防止 |
| REQ-PSP-006 | IF 既に処理済みの`eventId`を持つWebhookを受信した場合、THEN THE SYSTEM SHALL 状態を変更せず`200 OK`を返す | PSPは同一通知を再送する前提。受信側の冪等性 |
| REQ-PSP-007 | IF 現在の状態に対して適用できないWebhookを受信した場合、THEN THE SYSTEM SHALL 状態を変更せず`200 OK`を返し、警告としてログに記録する | Webhookの到達順序は保証されない。再送を誘発しないため200を返す |
| REQ-PSP-008 | THE SYSTEM SHALL Webhookの署名検証・重複判定・状態更新を単一のトランザクションで完結させ、REQ-NFR-004の時間内に応答する | 応答が遅いとPSPが再送してくる |

---

## 4. 非機能要件 (REQ-NFR)

演習として妥当な値を設定する。実運用では取引量・PSPのSLAに応じて再検討する。

| ID | 項目 | 値 | 備考 |
|---|---|---|---|
| REQ-NFR-001 | ディスパッチ走査の間隔 | 1秒 | 演習でのデモ応答性を優先。走査は`FOR UPDATE SKIP LOCKED`で行い、複数インスタンスでの二重取得を防ぐ |
| REQ-NFR-002 | PSP送信のリトライ上限 | 5回 | 指数バックオフ(1秒 → 2 → 4 → 8)を挟んで計5回送信する。待機中は行を`PENDING`へ戻し、次に確保してよい時刻を`next_attempt_at`に持たせる。超過で`FAILED`とし、以降は自動で再送せず人手で対応する。試行回数は送信失敗時ではなくレコード確保時に加算する |
| REQ-NFR-009 | 確保済みディスパッチの回収時間 | 1分 | `SENDING`のまま放置された行を`PENDING`とみなして再度走査対象に含める。送信直前のプロセス停止で行が永久に取り残されるのを防ぐ。バックオフの待機中は行を確保しないため、この時間には含まれない |
| REQ-NFR-010 | 1回の走査で確保する最大件数 | 10件 | 1周が長引くと確保したままの行が増えるため、REQ-NFR-009の回収時間との兼ね合いで決める |
| REQ-NFR-003 | PSP呼び出しのタイムアウト | 接続3秒 / 読み取り5秒 | |
| REQ-NFR-004 | Webhook受信の応答時間 | 3秒以内 | 超過するとPSP側が再送を開始する想定 |
| REQ-NFR-005 | 与信の有効期限 | 7日 |  |
| REQ-NFR-006 | 冪等性キーの保持期間 | 24時間 | 一般的な決済APIの慣行に合わせる |
| REQ-NFR-007 | Webhook `eventId` の保持期間 | 30日 | 遅延した再送にも対応できるよう送信側より長く取る |
| REQ-NFR-008 | 相関ID | 全ログ・ディスパッチレコード・Webhookペイロードに伝搬する | 1注文分の一連の処理を追跡可能にする |

---

## 5. 演習用PSPシミュレータの仕様 (REQ-SIM)

| ID | 要件 | 備考 |
|---|---|---|
| REQ-SIM-001 | THE SYSTEM SHALL 与信・売上確定・返金の各エンドポイントで、結果を含まない`202 Accepted`のみを即座に返す | 決済結果は必ずWebhookで通知する |
| REQ-SIM-002 | THE SYSTEM SHALL 受け付けから1〜5秒後に、結果を含むWebhookを送信する | 非同期性を再現するための遅延 |
| REQ-SIM-003 | THE SYSTEM SHALL 与信の可否を金額に基づくルールで決定する。金額の下2桁が`99`の場合は拒否し、それ以外は承認する | **ランダムにしない**。E2Eテストの再現性を確保するため |
| REQ-SIM-004 | THE SYSTEM SHALL 売上確定の可否を金額に基づくルールで決定する。金額の下2桁が`98`の場合は失敗し、それ以外は成功する | 同上 |
| REQ-SIM-005 | IF 既に受け付けた冪等性キーと同一のリクエストを受信した場合、THEN THE SYSTEM SHALL 新たな処理を行わず`202 Accepted`を返す | 送信側の冪等性が機能することを確認するため、PSP側にも実装する |
| REQ-SIM-006 | THE SYSTEM SHALL Webhookに共有シークレットによるHMAC署名ヘッダーを付与する | REQ-PSP-005の検証対象 |
| REQ-SIM-007 | THE SYSTEM SHALL 任意のWebhookを手動で再送できる手段を提供する | REQ-PSP-006(重複排除)の検証に用いる |

---

## 6. 未確定事項

以下は本書では確定させておらず、実装の過程で決める。

| 項目 | 扱い |
|---|---|
| APIのリクエスト/レスポンススキーマ | エンドポイントとヘッダーの要件は本書で定義済み(REQ-ORD-001/007/009, REQ-PSP-005/006)。フィールド単位の定義はControllerのDTOをもって仕様とし、必要ならspringdocでOpenAPIを生成する |
| DBのカラム定義・制約・インデックス | Flywayのマイグレーションファイル自体を仕様とする。二重管理を避けるため本書には記載しない |
| `SETTLEMENT_FAILED`となった注文の回復手段 | 本演習のスコープ外。手動でのDB操作を前提とする。残存する与信枠は自然失効に委ねる([ADR-0001](./adr/0001-exclude-void-from-scope.md)) |
| 部分売上確定(与信額の一部のみを確定する) | スコープ外。売上確定は与信額の全額に対して1回のみ行う |

# ADR-0001: Voidをスコープ外にする

## Status

Accepted

## Context

決済ドメインの設計では当初、与信(Authorize)・売上確定(Capture)・与信取消(Void)・返金(Refund)の4操作でPaymentを設計していた。

Voidは「Capture前に与信を取り消す」操作だが、本演習のMVP設計ではCaptureを出荷等の実業務トリガーを挟まず、与信成功後に即座に実行する方針とした。この場合、与信からCaptureまでの間はミリ秒単位となり、「その間にキャンセルする」というVoidの現実的な発生条件が存在しない。

またVoidは実装パターンとして、Capture・Refundと同じ「Dispatch → Webhook → PaymentOutcomePort呼び出し」というサイクルの繰り返しであり、実装期間の制約からスコープから除外とした

## Decision

Voidユースケースを本演習の実装スコープから除外する。Paymentは与信(Authorize)・売上確定(Capture)・返金(Refund)の3操作に統一する。

## Consequences

- `VoidAuthorizationUseCase` / `VoidAuthorizationService`、`Authorization`の`VOID_PENDING`/`VOIDED`状態、`PaymentVoided`イベント、PSPスタブの`/void`エンドポイントを設計・実装対象から削除した。
- 実装プランはステップ4(売上確定)、ステップ5(返金)、ステップ6(正常系/与信拒否/売上確定失敗/部分返金/重複Webhookの5シナリオ)に整理された。
- 将来、出荷確認など「与信とCaptureの間に実業務トリガーを挟む」設計に変更する場合は、Voidの必要性を再検討する。その際は本ADRをSupersededとし、新しいADRを起票する。

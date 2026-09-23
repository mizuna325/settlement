package com.example.settlement.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.example.settlement.shared.Money;

public class Payment {
    private long version;
    private final PaymentId paymentId;
    private final OrderId orderId;
    private final Money amount;
    private PaymentStatus paymentStatus;
    private Authorization authorization;

    /** 0..1。与信成功まで存在しない。 */
    private Capture capture;

    /** 0..n。部分返金を繰り返せるため、子エンティティの中で唯一コレクションになる。 */
    private final List<Refund> refunds;

    private Payment(long version, PaymentId paymentId, OrderId orderId, Money amount, PaymentStatus paymentStatus,
            Authorization authorization, Capture capture, List<Refund> refunds) {
        if (paymentId == null) {
            throw new IllegalArgumentException("paymentId must not be null.");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null.");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        if (paymentStatus == null) {
            throw new IllegalArgumentException("paymentStatus must not be null.");
        }
        if (authorization == null) {
            throw new IllegalArgumentException("authorization must not be null.");
        }
        this.version = version;
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.amount = amount;
        this.paymentStatus = paymentStatus;
        this.authorization = authorization;
        this.capture = capture;
        this.refunds = refunds == null ? new ArrayList<>() : new ArrayList<>(refunds);
    }

    /** 注文からの与信要求で決済を開始する(REQ-PAY-001)。 */
    public static Payment create(OrderId orderId, Money amount) {
        return new Payment(0, PaymentId.generate(), orderId, amount, PaymentStatus.AUTHORIZING,
                Authorization.create(AuthorizationId.generate(), amount), null, null);
    }

    /** 永続化から復元する。capture は与信成功まで、refunds は返金要求まで存在しない。 */
    public static Payment reconstruct(long version, PaymentId paymentId, OrderId orderId, Money amount,
            PaymentStatus paymentStatus, Authorization authorization, Capture capture, List<Refund> refunds) {
        return new Payment(version, paymentId, orderId, amount, paymentStatus, authorization, capture, refunds);
    }

    public long getVersion() {
        return this.version;
    }

    /**
     * 永続化で加算されたバージョンを書き戻す。リポジトリ実装からのみ呼ぶ(design.md §3)。
     *
     * <p>
     * Spring Data JDBC は {@code save()} が返すインスタンスにのみバージョンを加算するため、
     * 書き戻さないと同じインスタンスを再度保存したときに古い値で更新を試み、
     * 楽観ロックで失敗する。与信成功と売上確定を同一トランザクションで行う経路
     * (REQ-PAY-004)がこれに該当する。
     */
    public void applyPersistedVersion(long version) {
        this.version = version;
    }

    public PaymentId getPaymentId() {
        return this.paymentId;
    }

    public OrderId getOrderId() {
        return this.orderId;
    }

    public Money getAmount() {
        return this.amount;
    }

    public PaymentStatus getPaymentStatus() {
        return this.paymentStatus;
    }

    public Authorization getAuthorization() {
        return this.authorization;
    }

    public Capture getCapture() {
        return this.capture;
    }

    private void transitionTo(PaymentStatus next) {
        if (this.paymentStatus.canTransitionTo(next)) {
            this.paymentStatus = next;
            return;
        }
        throw new IllegalStateException("the current PaymentStatus can not transition to " + next.toString());
    }

    public void recordAuthorization(String pspReference, Instant authorizedAt, Duration validity) {
        this.getAuthorization().authorize(pspReference, authorizedAt, validity);
        transitionTo(PaymentStatus.AUTHORIZED);

    }

    public void declineAuthorization() {
        this.getAuthorization().decline();
        transitionTo(PaymentStatus.AUTH_DECLINED);
    }

    /**
     * 売上確定をPSPへ依頼する(REQ-PAY-004)。
     *
     * <p>
     * 呼び出し側が検査を忘れても自衛できるよう、不変条件はここで判定する(REQ-PAY-012)。
     *
     * @param amount 確定する金額。与信額を超えられない(REQ-PAY-005)
     * @param now    有効期限の判定に使う現在時刻(REQ-PAY-006)
     */
    public void capture(Money amount, Instant now) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        if (now == null) {
            throw new IllegalArgumentException("now must not be null.");
        }
        // REQ-PAY-010: 処理中の売上確定があるあいだは新たに依頼しない。二重ディスパッチの防止。
        if (this.capture != null && this.capture.isPending()) {
            throw new IllegalStateException("a pending Capture already exists.");
        }
        // REQ-PAY-005: 与信した金額を超えて確定できない。
        if (amount.isGreaterThan(this.authorization.getAmount())) {
            throw new IllegalStateException("the capture amount exceeds the authorized amount.");
        }
        // REQ-PAY-006: 与信には有効期限がある。
        Instant expiresAt = this.authorization.getExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(now)) {
            throw new IllegalStateException("the authorization has expired.");
        }
        this.capture = Capture.create(CaptureId.generate(), amount);
        transitionTo(PaymentStatus.CAPTURING);
    }

    /** 売上確定成功のWebhookを受けて確定させる。 */
    public void recordCapture(String pspReference, Instant capturedAt) {
        requireCapture().capture(pspReference, capturedAt);
        transitionTo(PaymentStatus.CAPTURED);
    }

    /** 売上確定失敗のWebhookを受けて終端へ落とす(REQ-ORD-005)。 */
    public void failCapture() {
        requireCapture().fail();
        transitionTo(PaymentStatus.CAPTURE_FAILED);
    }

    private Capture requireCapture() {
        if (this.capture == null) {
            throw new IllegalStateException("no Capture has been requested.");
        }
        return this.capture;
    }

    // ---- 返金 ----

    /** 読み取り専用。追加は requestRefund() を通す。 */
    public List<Refund> getRefunds() {
        return List.copyOf(this.refunds);
    }

    /**
     * 返金をPSPへ依頼する(REQ-ORD-009)。
     *
     * @param amount      返金額。全額でも一部でもよい
     * @param reason      返金の理由
     * @param requestedAt 要求を受け付けた時刻
     */
    public void requestRefund(Money amount, RefundReason reason, Instant requestedAt) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null.");
        }
        // REQ-PAY-007: 売上確定が完了していなければ返金できない。受け取っていない金銭は戻せない。
        if (this.capture == null || this.capture.getCaptureStatus() != CaptureStatus.CAPTURED) {
            throw new IllegalStateException("the capture has not been completed.");
        }
        // REQ-PAY-010: 処理中の返金があるあいだは新たに依頼しない。
        if (pendingRefund() != null) {
            throw new IllegalStateException("a pending Refund already exists.");
        }
        // REQ-PAY-008: 確定済みと処理中を合わせた累計が売上確定額を超えられない。
        // 処理中(PENDING)を含めるのは、結果待ちのあいだに超過する要求を通さないため。
        if (committedRefundTotal().plus(amount).isGreaterThan(this.capture.getAmount())) {
            throw new IllegalStateException("the refund total would exceed the captured amount.");
        }
        this.refunds.add(Refund.create(RefundId.generate(), amount, reason, requestedAt));
        transitionTo(PaymentStatus.REFUNDING);
    }

    /**
     * 返金完了のWebhookを受けて確定させる(REQ-PAY-009)。
     *
     * <p>
     * 累計が売上確定額に達していれば REFUNDED、未満なら PARTIALLY_REFUNDED。
     */
    public void confirmRefund(String pspReference) {
        requirePendingRefund().refund(pspReference);
        transitionTo(refundedTotal().equals(requireCapture().getAmount())
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED);
    }

    /**
     * 返金失敗のWebhookを受けて直前の状態へ戻す(REQ-PAY-011)。
     *
     * <p>
     * 「直前の状態」を列として保持せず、確定済みの返金累計から導出する(design.md §3)。
     * 累計が0なら一度も返金できていないので CAPTURED、0より大きければ PARTIALLY_REFUNDED。
     */
    public void failRefund() {
        requirePendingRefund().fail();
        transitionTo(refundedTotal().amount() == 0
                ? PaymentStatus.CAPTURED
                : PaymentStatus.PARTIALLY_REFUNDED);
    }

    /**
     * REQ-PAY-013: 与信が成立した金額。成立していなければ0。
     *
     * <p>
     * 依頼した金額ではなく<strong>確定した金額</strong>を返す。PSPが拒否した場合や
     * 結果待ちの場合に金額を見せると、押さえられていない額を押さえたように読める。
     * {@link #refundedTotal()} が確定済みの返金だけを数えるのと同じ基準。
     */
    public Money authorizedAmount() {
        return this.authorization.getAuthorizationStatus() == AuthorizationStatus.AUTHORIZED
                ? this.authorization.getAmount()
                : new Money(0, this.amount.unit());
    }

    /**
     * REQ-PAY-013: 売上が確定した金額。確定していなければ0。
     *
     * <p>
     * 売上確定は起きていない場合があるため capture は null をとりうる(0..1)。
     * 失敗した場合も0を返す。理由は {@link #authorizedAmount()} と同じ。
     */
    public Money capturedAmount() {
        return this.capture != null && this.capture.getCaptureStatus() == CaptureStatus.CAPTURED
                ? this.capture.getAmount()
                : new Money(0, this.amount.unit());
    }

    /**
     * 確定済みの返金累計(REQ-PAY-009 / REQ-PAY-011 / REQ-PAY-013 が使う)。
     *
     * <p>
     * REQ-PAY-008 の超過判定とは集計対象が異なる。あちらは処理中の返金も数える必要がある。
     */
    public Money refundedTotal() {
        return this.refunds.stream()
                .filter(Refund::isRefunded)
                .map(Refund::getAmount)
                .reduce(new Money(0, this.amount.unit()), Money::plus);
    }

    /** 確定済みと処理中を合わせた累計(REQ-PAY-008 の超過判定が使う)。 */
    private Money committedRefundTotal() {
        return this.refunds.stream()
                .filter(refund -> refund.isRefunded() || refund.isPending())
                .map(Refund::getAmount)
                .reduce(new Money(0, this.amount.unit()), Money::plus);
    }

    /** REQ-PAY-010 により、処理中の返金はたかだか1件しか存在しない。 */
    private Refund pendingRefund() {
        return this.refunds.stream().filter(Refund::isPending).findFirst().orElse(null);
    }

    private Refund requirePendingRefund() {
        Refund pending = pendingRefund();
        if (pending == null) {
            throw new IllegalStateException("no pending Refund exists.");
        }
        return pending;
    }
}

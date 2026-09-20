package com.example.settlement.payment.domain;

import java.time.Duration;
import java.time.Instant;

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

    private Payment(long version, PaymentId paymentId, OrderId orderId, Money amount, PaymentStatus paymentStatus,
            Authorization authorization, Capture capture) {
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
    }

    /** 注文からの与信要求で決済を開始する(REQ-PAY-001)。 */
    public static Payment create(OrderId orderId, Money amount) {
        return new Payment(0, PaymentId.generate(), orderId, amount, PaymentStatus.AUTHORIZING,
                Authorization.create(AuthorizationId.generate(), amount), null);
    }

    /** 永続化から復元する。capture は与信成功まで存在しないため null を取りうる。 */
    public static Payment reconstruct(long version, PaymentId paymentId, OrderId orderId, Money amount,
            PaymentStatus paymentStatus, Authorization authorization, Capture capture) {
        return new Payment(version, paymentId, orderId, amount, paymentStatus, authorization, capture);
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
}

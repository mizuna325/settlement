package com.example.settlement.payment.domain;

import com.example.settlement.shared.Money;

public class Payment {
    private long version;
    private final PaymentId paymentId;
    private final OrderId orderId;
    private final Money amount;
    private PaymentStatus paymentStatus;
    private Authorization authorization;

    private Payment(long version, PaymentId paymentId, OrderId orderId, Money amount, PaymentStatus paymentStatus,
            Authorization authorization) {
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
    }

    /** 注文からの与信要求で決済を開始する(REQ-PAY-001)。 */
    public static Payment create(OrderId orderId, Money amount) {
        return new Payment(0, PaymentId.generate(), orderId, amount, PaymentStatus.AUTHORIZING,
                Authorization.create(AuthorizationId.generate(), amount));
    }

    /** 永続化から復元する。 */
    public static Payment reconstruct(long version, PaymentId paymentId, OrderId orderId, Money amount,
            PaymentStatus paymentStatus, Authorization authorization) {
        return new Payment(version, paymentId, orderId, amount, paymentStatus, authorization);
    }

    public long getVersion() {
        return this.version;
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
}

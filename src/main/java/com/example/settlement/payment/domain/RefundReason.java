package com.example.settlement.payment.domain;

/**
 * 返金の理由(REQ-ORD-009)。
 *
 * <p>
 * 列挙にしていないのは、業務として定まった分類が無く、運用の記録として自由記述を残す
 * 位置づけであるため(requirements.md にも値の定義がない)。
 */
public record RefundReason(String reason) {
    public RefundReason {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be null or blank.");
        }
    }
}

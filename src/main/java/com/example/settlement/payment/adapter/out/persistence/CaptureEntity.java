package com.example.settlement.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import com.example.settlement.payment.domain.Capture;
import com.example.settlement.payment.domain.CaptureId;
import com.example.settlement.payment.domain.CaptureStatus;
import com.example.settlement.shared.Currency;
import com.example.settlement.shared.Money;

/** payment_captures の永続化専用モデル。payment_id は @MappedCollection が書く。 */
@Table("payment_captures")
record CaptureEntity(@Id UUID captureId,
        long amount,
        String currency,
        String pspReference,
        String status,
        Instant capturedAt) {

    /** 与信成功まで Capture は存在しないため null を取りうる。 */
    static CaptureEntity from(Capture capture) {
        if (capture == null) {
            return null;
        }
        return new CaptureEntity(
                capture.getCaptureId().captureId(),
                capture.getAmount().amount(),
                capture.getAmount().unit().name(),
                capture.getPspReference(),
                capture.getCaptureStatus().name(),
                capture.getCapturedAt());
    }

    Capture toDomain() {
        return Capture.reconstruct(
                new CaptureId(captureId),
                new Money(amount, Currency.valueOf(currency)),
                pspReference,
                CaptureStatus.valueOf(status),
                capturedAt);
    }
}

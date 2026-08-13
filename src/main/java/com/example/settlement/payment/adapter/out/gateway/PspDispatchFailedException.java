package com.example.settlement.payment.adapter.out.gateway;

import java.util.UUID;

/**
 * PSPへの送信が失敗したことを表す。
 * PspDispatchRelay はこれを捕捉してリトライ判断を行う(REQ-PSP-004)。
 */
public class PspDispatchFailedException extends RuntimeException {

    private final UUID dispatchEventId;

    PspDispatchFailedException(UUID dispatchEventId, Throwable cause) {
        super("PSPへの送信に失敗した: dispatchEventId=" + dispatchEventId, cause);
        this.dispatchEventId = dispatchEventId;
    }

    public UUID getDispatchEventId() {
        return this.dispatchEventId;
    }
}

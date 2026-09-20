package com.example.settlement.pspsimulator;

import org.springframework.stereotype.Component;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;

@Component
class PspIdempotencyKeyCleaner {

    private final PspIdempotencyKeyStore pspIdempotencyKeyStore;
    private final PspSimulatorProperty pspSimulatorProperty;
    private final Clock clock;

    PspIdempotencyKeyCleaner(PspIdempotencyKeyStore pspIdempotencyKeyStore, PspSimulatorProperty pspSimulatorProperty,
            Clock clock) {
        this.pspIdempotencyKeyStore = pspIdempotencyKeyStore;
        this.pspSimulatorProperty = pspSimulatorProperty;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    int deleteExpired() {
        return pspIdempotencyKeyStore.deleteExpired(this.clock.instant(),
                pspSimulatorProperty.idempotencyKeyRetention());
    }
}

package com.example.settlement.payment.adapter.out.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "settlement.psp")
record WebhookEventProperty(Duration webhookEventRetention) {

}

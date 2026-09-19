package com.example.settlement.payment.adapter.in.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "settlement.psp")
record WebhookProperty(String webhookSecret, Duration webhookSignatureTolerance) {

}

package com.example.settlement.payment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * 与信の設定値。読み手が application 層にいるため adapter 配下の既存2レコードには足せない
 * (ArchUnit の applicationMustNotDependOnAdapters)。
 *
 * @param authorizationValidity 与信の有効期間(REQ-NFR-005)
 */
@ConfigurationProperties(prefix = "settlement.psp")
public record AuthorizationProperties(Duration authorizationValidity) {
}
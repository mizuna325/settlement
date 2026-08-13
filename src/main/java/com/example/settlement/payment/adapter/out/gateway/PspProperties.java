package com.example.settlement.payment.adapter.out.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PSP呼び出しの設定値(design.md §設定値)。
 * 実装にハードコードせず application.properties から注入し、テストで短縮できるようにする。
 */
@ConfigurationProperties(prefix = "settlement.psp")
record PspProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}

package com.example.settlement;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * 可観測性の第2段階(design.md §8.3)。span が OTLP で外部へ送られることを確認する。
 *
 * <p>
 * 第1段階との違いは送出先だけで、アプリケーションのコードは変わらない。変わるのは
 * {@code opentelemetry-exporter-otlp} が classpath に居るかと、プロパティの2点のみ。
 * この「コードが変わらない」という主張は、依存を外せばこのテストだけが落ちる形で
 * 裏付けられる。
 *
 * <p>
 * 送出先には Jaeger ではなくJDK内蔵のHTTPサーバーを立てる。テストがDockerの有無に
 * 依存しなくなり、CIでもそのまま走る。OTLP over HTTP の受け口は
 * {@code POST /v1/traces} に protobuf を投げるだけなので、受け取った本文に
 * traceId のバイト列が含まれているかを見れば足りる。本文を解析する必要はない。
 *
 * <p>
 * 他のテストは {@code management.tracing.export.otlp.enabled=false}
 * (src/test/resources/application.properties)で送出を止めている。Jaeger が
 * 居ない状態で有効にすると、全テストで接続エラーが出続けるため。ここだけ上書きする。
 */
@SpringBootTest(properties = {
        "management.tracing.export.otlp.enabled=true",
        // 既定では数秒ためてから送る。テストが待つ時間を短くする。
        "management.opentelemetry.tracing.export.schedule-delay=100ms" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtlpExportTest {

    /** 受け取ったリクエスト本文。送信はエクスポーターの別スレッドから来る。 */
    private static final ConcurrentLinkedQueue<byte[]> received = new ConcurrentLinkedQueue<>();

    private static final HttpServer collector = startCollector();

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://localhost:" + collector.getAddress().getPort() + "/v1/traces");
    }

    @AfterAll
    static void stopCollector() {
        collector.stop(0);
    }

    @Autowired
    Tracer tracer;

    @Test
    @DisplayName("第2段階: 終了した span が OTLP で送出先へ届く")
    void endedSpansReachTheCollector() {
        Span span = tracer.nextSpan().name("otlp-export-check").start();
        String traceId;
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            traceId = span.context().traceId();
        } finally {
            span.end();
        }

        // protobuf は trace_id を16バイトの生のバイト列として持つ。16進表記を
        // バイトに戻して本文から探せば、この span が届いたことを特定できる。
        byte[] expected = HexFormat.of().parseHex(traceId);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> received.stream().anyMatch(body -> contains(body, expected)));

        assertTrue(received.stream().anyMatch(body -> contains(body, expected)),
                "終了した span が送出されていない。exporter が classpath に無いか、"
                        + "management.tracing.export.otlp.enabled が false のまま");
    }

    /**
     * OTLP over HTTP の受け口を立てる。
     *
     * <p>
     * ポートは 0 を指定してOSに空きを選ばせる。固定すると、並行して走る他のテストや
     * 開発機で動いている何かと衝突する。
     */
    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/traces", exchange -> {
                received.add(exchange.getRequestBody().readAllBytes());
                // 成功時は ExportTraceServiceResponse を返す。フィールドを1つも持たない
                // メッセージは長さ0のバイト列として正当なので、空の本文で良い。
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** haystack が needle を部分列として含むか。 */
    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

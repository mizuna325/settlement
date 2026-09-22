package com.example.settlement.payment.adapter.out.outbox;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Outbox の行を跨いでトレースを繋ぐ(design.md §8.1 の境界①)。
 *
 * <p>
 * 行を積むのはHTTPリクエストのスレッド、送るのは @Scheduled の別スレッドで、しかも
 * 数秒後になる。スレッドローカルに置かれたコンテキストは越えないため、W3C Trace Context
 * の文字列に落として行に保存し、送信時に復元する。
 *
 * <p>
 * 取り出しと復元でヘッダの書式を直接触らないよう、{@link Propagator} に任せている。
 * 形式が変わってもここは影響を受けない。
 */
@Component
class DispatchTraceContext {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    DispatchTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * 現在のトレースコンテキストを文字列にする。
     *
     * @return traceparent。トレースが有効でなければ null
     */
    String capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * 保存しておいたコンテキストの続きとして span を開始する。
     *
     * <p>
     * 親子で繋ぐ(design.md §8.5)。1注文分の処理が1本のトレースになる代わりに、
     * 親である POST /orders の span は既に終了しているため、親の所要時間が子を含まない。
     *
     * @param traceparent 行に保存されていた値。null なら新しいトレースとして始める
     */
    Span startSpan(String traceparent, String name) {
        if (traceparent == null) {
            return tracer.nextSpan().name(name).start();
        }
        Map<String, String> carrier = Map.of(TRACEPARENT, traceparent);
        return propagator.extract(carrier, Map::get).name(name).start();
    }

    Tracer tracer() {
        return this.tracer;
    }
}

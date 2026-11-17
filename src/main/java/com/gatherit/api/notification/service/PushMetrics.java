package com.gatherit.api.notification.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 푸시 발송 집계 — Micrometer 카운터 gatherit.push.sent{platform, result}.
 * 인메모리(재시작 시 0부터) — "지금 푸시가 나가고 있나 / 실패율이 튀나" 운영 신호용이지 영구 통계가 아님.
 *
 * result:
 *  - ok            : APNs/FCM 2xx
 *  - token_cleaned : 죽은 토큰(410/UNREGISTERED 등) 감지 → 토큰 제거
 *  - fail          : 비 2xx 응답 (토큰 정리 외)
 *  - error         : 예외 (타임아웃/네트워크 등)
 */
@Component
public class PushMetrics {

    public static final String METRIC = "gatherit.push.sent";

    private final MeterRegistry registry;

    public PushMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String platform, String result) {
        registry.counter(METRIC, "platform", platform, "result", result).increment();
    }
}

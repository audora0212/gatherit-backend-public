package com.gatherit.api.auth.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 인증 refresh 결과 집계 — Micrometer 카운터 gatherit.auth.refresh{result}.
 * /actuator/prometheus 로 노출 → Grafana 에서 forced_logout 비율 급증 시 알림 가능.
 *
 * result:
 *  - rotated          : 현재 토큰 일치 → 정상 회전(새 access+refresh)
 *  - grace_recovered  : 허용 시간 안에 직전 토큰으로 재시도하여 응답 유실 후 세션을 복구함
 *  - attempt_recovered: 저장된 회전 요청 증명으로 응답 유실 복구 (만료 연장 없음)
 *  - forced_logout    : 호환 메트릭 이름 유지. 제출 요청을 거절하되 현재 서버 세션은 보존.
 *
 * forced_logout / (전체) = "세션 끊김률" — 이 값이 튀면 회전 경합/클라 refresh 버그를 의심.
 */
@Component
public class AuthMetrics {

    public static final String REFRESH_METRIC = "gatherit.auth.refresh";

    private final MeterRegistry registry;

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRefresh(String result) {
        registry.counter(REFRESH_METRIC, "result", result).increment();
    }
}

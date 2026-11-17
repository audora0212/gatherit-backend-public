package com.gatherit.api.auth.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthMetrics — refresh 결과 카운터가 result 태그별로 올바르게 집계되는지 검증.
 * SimpleMeterRegistry(인메모리) 사용 → DB/앱 부팅 없이 메트릭 로직만 로컬 검증.
 */
class AuthMetricsTest {

    private SimpleMeterRegistry registry;
    private AuthMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AuthMetrics(registry);
    }

    private double count(String result) {
        Counter c = registry.find(AuthMetrics.REFRESH_METRIC).tag("result", result).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("result 태그별로 분리 집계된다")
    void recordsByResultTag() {
        metrics.recordRefresh("rotated");
        metrics.recordRefresh("rotated");
        metrics.recordRefresh("grace_recovered");
        metrics.recordRefresh("forced_logout");

        assertThat(count("rotated")).isEqualTo(2.0);
        assertThat(count("grace_recovered")).isEqualTo(1.0);
        assertThat(count("forced_logout")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("메트릭 이름은 prometheus 노출 규약과 일치한다")
    void metricName() {
        metrics.recordRefresh("forced_logout");
        assertThat(registry.find(AuthMetrics.REFRESH_METRIC).counter()).isNotNull();
        assertThat(AuthMetrics.REFRESH_METRIC).isEqualTo("gatherit.auth.refresh");
    }
}

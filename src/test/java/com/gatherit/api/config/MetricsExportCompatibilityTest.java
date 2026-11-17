package com.gatherit.api.config;

import com.gatherit.api.auth.service.AuthMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Actuator export in an isolated loopback-only context: no DB/AWS/app bootstrap. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MetricsExportCompatibilityTest.TestApplication.class,
        properties = {"spring.profiles.active=metrics-contract", "server.address=127.0.0.1",
                "spring.jpa.hibernate.ddl-auto=none", "server.tomcat.mbeanregistry.enabled=true",
                "management.endpoints.web.exposure.include=health,prometheus",
                "management.metrics.tags.application=gatherit-server",
                "management.metrics.distribution.percentiles-histogram.http.server.requests=true",
                "management.metrics.distribution.slo.http.server.requests=100ms,200ms,300ms,500ms,1s,2s,5s"})
@AutoConfigureMetrics
class MetricsExportCompatibilityTest {
    private static final String HTTP = "http_server_requests_seconds";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final List<String> ALLOY_BUCKETS = List.of("0.001", "0.004194304", "0.008388607", "0.016777216",
            "0.033554431", "0.067108864", "0.1", "0.2", "0.3", "0.5", "1.0", "2.0", "5.0",
            "8.589934591", "15.748213416", "22.906492245", "30.0", "+Inf");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    @LocalServerPort int port;
    @Autowired AuthMetrics authMetrics;

    @Test
    void actuatorPreservesClassicHistogramAndDashboardLabels() throws Exception {
        for (String result : List.of("rotated", "grace_recovered", "attempt_recovered", "forced_logout")) {
            authMetrics.recordRefresh(result);
        }
        assertThat(get("/metrics-contract/ping").statusCode()).isEqualTo(200);
        var response = get("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        String text = response.body();
        assertThat(text).contains("# TYPE " + HTTP + " histogram");

        var buckets = samples(text, HTTP + "_bucket").stream()
                .filter(line -> labels(line).get("uri").equals("/metrics-contract/ping")).toList();
        assertThat(buckets.stream().map(line -> labels(line).get("le")).collect(Collectors.toSet()))
                .containsAll(ALLOY_BUCKETS);
        Set<String> expected = Set.of("application", "error", "exception", "method", "outcome", "status", "uri", "le");
        for (String bucket : buckets) {
            assertThat(labels(bucket).keySet()).isEqualTo(expected);
            assertThat(labels(bucket)).containsEntry("application", "gatherit-server")
                    .containsEntry("status", "200").containsEntry("method", "GET");
        }
        for (String suffix : List.of("_count", "_sum", "_max")) {
            var series = samples(text, HTTP + suffix).stream()
                    .filter(line -> "/metrics-contract/ping".equals(labels(line).get("uri"))).toList();
            assertThat(series).hasSize(1);
            assertThat(labels(series.get(0)).keySet())
                    .isEqualTo(Set.of("application", "error", "exception", "method", "outcome", "status", "uri"));
        }
        var refresh = samples(text, "gatherit_auth_refresh_total");
        assertThat(refresh).hasSize(4);
        assertThat(refresh.stream().map(line -> labels(line).get("result")).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("rotated", "grace_recovered", "attempt_recovered", "forced_logout");
        for (String line : refresh) {
            assertThat(labels(line).keySet()).isEqualTo(Set.of("application", "result"));
            assertThat(labels(line)).containsEntry("application", "gatherit-server");
        }
        for (String metric : List.of("jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_threads_live_threads",
                "process_cpu_usage", "process_uptime_seconds", "tomcat_sessions_active_current_sessions")) {
            assertThat(samples(text, metric)).as(metric).isNotEmpty();
            assertThat(labels(samples(text, metric).get(0))).containsEntry("application", "gatherit-server");
        }
        // Contains only isolated test/JVM metrics, never production telemetry or credentials.
        Path report = Path.of("build/reports/metrics-contract/exposition.prom");
        Files.createDirectories(report.getParent());
        Files.writeString(report, text, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/plain;version=0.0.4").GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> samples(String text, String metric) {
        return text.lines().filter(line -> line.startsWith(metric + "{") || line.startsWith(metric + " ")).toList();
    }

    private static Map<String, String> labels(String line) {
        Map<String, String> result = new TreeMap<>();
        var matcher = LABEL.matcher(line);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2));
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class})
    @Import({AuthMetrics.class, ProbeController.class})
    static class TestApplication {
        @Bean
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/metrics-contract/ping")
        String ping() { return "ok"; }
    }
}

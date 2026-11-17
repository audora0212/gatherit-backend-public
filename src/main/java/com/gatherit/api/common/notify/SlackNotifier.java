package com.gatherit.api.common.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Slack incoming webhook으로 메시지를 전송합니다.
 * 실패해도 throw 안 함 — 로그만. webhook URL 미설정 시 no-op.
 *
 * 용도:
 *   - 500 에러 알림 (GlobalExceptionHandler 에서 호출)
 *   - 서버 시작/종료 (LifecycleHook 에서 호출)
 *   - 배치 결과 (cron 에서 호출)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackNotifier {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    @Value("${spring.profiles.active:local}")
    private String profile;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper om = new ObjectMapper();

    @Async
    public void send(String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        try {
            String body = om.writeValueAsString(Map.of("text", "[%s] %s".formatted(profile, text)));
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("Slack webhook responded {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Slack notify failed", e);
        }
    }

    @Async
    public void error(String title, Throwable e) {
        String stack = e == null ? "" : "\n```%s```".formatted(briefStack(e));
        send(":rotating_light: *%s*\n%s%s".formatted(title, e == null ? "" : e.getMessage(), stack));
    }

    private String briefStack(Throwable e) {
        StackTraceElement[] st = e.getStackTrace();
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        for (int i = 0; i < Math.min(5, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        return sb.toString();
    }
}

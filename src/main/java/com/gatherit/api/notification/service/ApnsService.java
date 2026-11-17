package com.gatherit.api.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * APNs HTTP/2 직접 호출 — Expo Push Service 우회.
 *
 * Expo Push Service 사용 시 알림 sender 가 Expo 로 표시되어 receiver 의 알림 배너
 * 아이콘이 Expo default 아이콘으로 뜨는 한계를 회피. Apple APNs 에 직접 push 하면
 * apns-topic 의 bundle ID 와 매칭된 앱(=GatherIt) 의 아이콘이 정확히 표시됨.
 *
 * 인증: APNs Auth Key (.p8) 로 서명한 ES256 JWT (Provider Token).
 *      JWT 는 1시간 유효 — 50분마다 lazy 갱신 (Apple 권장 ≥ 20분 마진).
 *
 * 환경변수 (application.yml):
 *   apns.key-path:  /path/to/provider-key
 *   apns.key-id:    Apple Developer 의 APNs Key ID (10자)
 *   apns.team-id:   Apple Developer Team ID (10자, example)
 *   apns.topic:     bundle ID (com.audora.gatherit)
 *   apns.production: true=prod APNs / false=sandbox (TestFlight + AppStore 는 prod)
 *
 * APNs 끄기: apns.key-path 를 빈 문자열로 두면 init 시 disabled 모드 (push silent skip).
 */
@Slf4j
@Component
public class ApnsService {

    private static final String PROD_HOST = "https://api.push.apple.com";
    private static final String SANDBOX_HOST = "https://api.sandbox.push.apple.com";
    private static final Duration JWT_TTL = Duration.ofMinutes(50); // Apple 권장 ≤ 60분

    private final UserRepository userRepository;
    private final PushMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String keyPath;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final boolean production;

    private ECPrivateKey privateKey;       // .p8 파싱 결과 — disabled 모드면 null
    private volatile String cachedJwt;
    private volatile Instant jwtIssuedAt;
    private boolean enabled;

    public ApnsService(UserRepository userRepository,
                       PushMetrics metrics,
                       @Value("${apns.key-path:}") String keyPath,
                       @Value("${apns.key-id:}") String keyId,
                       @Value("${apns.team-id:}") String teamId,
                       @Value("${apns.topic:}") String topic,
                       @Value("${apns.production:true}") boolean production) {
        this.userRepository = userRepository;
        this.metrics = metrics;
        this.keyPath = keyPath;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.production = production;
    }

    @PostConstruct
    void init() {
        if (keyPath == null || keyPath.isBlank()) {
            log.warn("APNs disabled — apns.key-path 미설정 (push 호출 시 silent skip)");
            enabled = false;
            return;
        }
        try {
            String pem = Files.readString(Path.of(keyPath));
            String base64 = pem.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                               .replaceAll("-----END PRIVATE KEY-----", "")
                               .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory kf = KeyFactory.getInstance("EC");
            this.privateKey = (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            this.enabled = true;
            log.info("APNs enabled — keyId={} teamId={} topic={} production={}",
                    keyId, teamId, topic, production);
        } catch (Exception e) {
            log.error("APNs init 실패 — disabled 모드. cause={}", e.toString());
            enabled = false;
        }
    }

    @Async
    public void sendToUser(Long userId, String title, String body, Map<String, Object> data) {
        if (!enabled) return;
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;
            String token = user.getExpoPushToken(); // 컬럼 재사용 — 의미는 native APNs token
            if (token == null || token.isBlank()) return;
            // android 토큰(FCM)은 APNs 로 보내면 실패 → FcmService 가 처리. null/ios 만 APNs.
            if ("android".equalsIgnoreCase(user.getPushPlatform())) return;

            // 옛 ExpoToken (`ExponentPushToken[...]`) 이 남아있으면 즉시 무효화 — 사용자 폰이
            // 다음 진입 시 native APNs token 으로 재등록. v0.1 → v0.2 마이그레이션 정리용.
            if (token.startsWith("ExponentPushToken")) {
                log.info("Stale ExpoToken detected — user={} cleared (will re-register as native APNs)", userId);
                user.updateExpoPushToken(null);
                userRepository.save(user);
                return;
            }

            Map<String, Object> aps = Map.of(
                    "alert", Map.of(
                            "title", title != null ? title : "GatherIt",
                            "body", body != null ? body : ""
                    ),
                    "sound", "default"
            );
            Map<String, Object> payload = Map.of(
                    "aps", aps,
                    "data", data != null ? data : Map.of()
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create((production ? PROD_HOST : SANDBOX_HOST) + "/3/device/" + token))
                    .header("authorization", "bearer " + currentJwt())
                    .header("apns-topic", topic)
                    .header("apns-push-type", "alert")
                    .header("content-type", "application/json")
                    // read 타임아웃 — APNs 지연 시 @Async 워커가 무한 대기하며 큐에 적체되는 것 방지.
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200) { metrics.record("apns", "ok"); return; }

            // 410 Gone = BadDeviceToken (앱 삭제/권한 회수). token 제거.
            if (status == 410) {
                log.info("APNs 410 BadDeviceToken — user={} token 제거", userId);
                user.updateExpoPushToken(null);
                userRepository.save(user);
                metrics.record("apns", "token_cleaned");
                return;
            }
            // 400 = malformed (token 형식 오류 등) — token 제거 후 다음에 재발급 받도록.
            if (status == 400) {
                String reason = parseReason(resp.body());
                if ("BadDeviceToken".equals(reason) || "DeviceTokenNotForTopic".equals(reason)) {
                    log.warn("APNs 400 {} — user={} token 제거", reason, userId);
                    user.updateExpoPushToken(null);
                    userRepository.save(user);
                    metrics.record("apns", "token_cleaned");
                    return;
                }
                log.warn("APNs 400 reason={} body={}", reason, abbrev(resp.body()));
                metrics.record("apns", "fail");
                return;
            }
            log.warn("APNs send fail user={} status={} body={}", userId, status, abbrev(resp.body()));
            metrics.record("apns", "fail");
        } catch (Exception e) {
            log.warn("APNs send error user={} : {}", userId, e.getMessage());
            metrics.record("apns", "error");
        }
    }

    public void sendToUsers(List<Long> userIds, String title, String body, Map<String, Object> data) {
        if (userIds == null || userIds.isEmpty()) return;
        for (Long uid : userIds) sendToUser(uid, title, body, data);
    }

    /** 50분 캐시 — 만료된 경우 새로 서명. */
    private synchronized String currentJwt() {
        Instant now = Instant.now();
        if (cachedJwt != null && jwtIssuedAt != null
                && now.isBefore(jwtIssuedAt.plus(JWT_TTL))) {
            return cachedJwt;
        }
        cachedJwt = Jwts.builder()
                .header().add("kid", keyId).add("typ", "JWT").and()
                .issuer(teamId)
                .issuedAt(Date.from(now))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
        jwtIssuedAt = now;
        return cachedJwt;
    }

    private String parseReason(String body) {
        try {
            return mapper.readTree(body).path("reason").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}

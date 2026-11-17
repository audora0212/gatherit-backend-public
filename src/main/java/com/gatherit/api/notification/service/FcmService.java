package com.gatherit.api.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android FCM HTTP v1 직접 호출 — ApnsService 의 안드로이드 대응.
 *
 * 앱은 getDevicePushTokenAsync() 로 네이티브 토큰을 받는다(iOS=APNs, Android=raw FCM).
 * iOS 토큰은 ApnsService 가, Android 토큰(user.pushPlatform="android")은 이 서비스가 전송.
 *
 * 인증: Firebase 서비스 계정 JSON 의 RSA 키로 RS256 JWT 서명 → OAuth2 토큰 교환
 *      (https://oauth2.googleapis.com/token) → access_token(≈1h) 캐시 → FCM v1 호출 시 Bearer.
 * firebase-admin SDK 미사용(무거운 의존성 회피) — APNs 와 동일하게 핸드롤.
 *
 * 환경변수 (application.yml):
 *   fcm.project-id:        Firebase 프로젝트 ID (example)
 *   fcm.credentials-path:  서비스 계정 JSON 경로 (/path/to/provider-key)
 * credentials-path 가 비면 disabled 모드 (push silent skip).
 */
@Slf4j
@Component
public class FcmService {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5); // access_token 만료 5분 전 갱신

    private final UserRepository userRepository;
    private final PushMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String credentialsPath;
    private String projectId;

    private RSAPrivateKey privateKey;   // 서비스 계정 RSA 키 — disabled 면 null
    private String clientEmail;
    private String sendUrl;             // https://fcm.googleapis.com/v1/projects/{projectId}/messages:send
    private boolean enabled;

    private volatile String cachedToken;
    private volatile Instant tokenExpiry;

    public FcmService(UserRepository userRepository,
                      PushMetrics metrics,
                      @Value("${fcm.project-id:}") String projectId,
                      @Value("${fcm.credentials-path:}") String credentialsPath) {
        this.userRepository = userRepository;
        this.metrics = metrics;
        this.projectId = projectId;
        this.credentialsPath = credentialsPath;
    }

    @PostConstruct
    void init() {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("FCM disabled — fcm.credentials-path 미설정 (Android push 호출 시 silent skip)");
            enabled = false;
            return;
        }
        try {
            JsonNode sa = mapper.readTree(Files.readString(Path.of(credentialsPath)));
            this.clientEmail = sa.path("client_email").asText();
            if (projectId == null || projectId.isBlank()) {
                this.projectId = sa.path("project_id").asText();
            }
            String pem = sa.path("private_key").asText();
            String base64 = pem.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                               .replaceAll("-----END PRIVATE KEY-----", "")
                               .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            this.privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            this.sendUrl = "https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send";
            this.enabled = true;
            log.info("FCM enabled — projectId={} clientEmail={}", projectId, clientEmail);
        } catch (Exception e) {
            log.error("FCM init 실패 — disabled 모드. cause={}", e.toString());
            enabled = false;
        }
    }

    @Async
    public void sendToUser(Long userId, String title, String body, Map<String, Object> data) {
        if (!enabled) return;
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;
            // 이 서비스는 android 토큰만 — iOS/null 은 ApnsService 담당.
            if (!"android".equalsIgnoreCase(user.getPushPlatform())) return;
            String token = user.getExpoPushToken();
            if (token == null || token.isBlank()) return;
            if (token.startsWith("ExponentPushToken")) {
                user.updateExpoPushToken(null);
                userRepository.save(user);
                return;
            }

            Map<String, String> dataStr = new HashMap<>();
            if (data != null) data.forEach((k, v) -> { if (v != null) dataStr.put(k, String.valueOf(v)); });

            Map<String, Object> message = Map.of(
                    "token", token,
                    "notification", Map.of(
                            "title", title != null ? title : "GatherIt",
                            "body", body != null ? body : ""
                    ),
                    "data", dataStr,
                    "android", Map.of("priority", "HIGH")
            );
            String payload = mapper.writeValueAsString(Map.of("message", message));

            String accessToken = currentAccessToken();
            if (accessToken == null) { log.warn("FCM access token 없음 — skip user={}", userId); return; }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sendUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    // read 타임아웃 — FCM 지연 시 @Async 워커가 무한 대기하며 큐에 적체되는 것 방지.
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200) { metrics.record("fcm", "ok"); return; }

            // 404 UNREGISTERED / 400 INVALID_ARGUMENT(bad token) → 토큰 제거 후 재발급 유도.
            String errCode = parseFcmErrorCode(resp.body());
            // INVALID_ARGUMENT 는 토큰 죽음이 아니라 페이로드 형식 문제일 수 있어 토큰 삭제에서 제외 — warn 만.
            if (status == 404 || "UNREGISTERED".equals(errCode) || "SENDER_ID_MISMATCH".equals(errCode)) {
                log.info("FCM {} ({}) — user={} token 제거", status, errCode, userId);
                user.updateExpoPushToken(null);
                userRepository.save(user);
                metrics.record("fcm", "token_cleaned");
                return;
            }
            log.warn("FCM send fail user={} status={} body={}", userId, status, abbrev(resp.body()));
            metrics.record("fcm", "fail");
        } catch (Exception e) {
            log.warn("FCM send error user={} : {}", userId, e.getMessage());
            metrics.record("fcm", "error");
        }
    }

    public void sendToUsers(List<Long> userIds, String title, String body, Map<String, Object> data) {
        if (userIds == null || userIds.isEmpty()) return;
        for (Long uid : userIds) sendToUser(uid, title, body, data);
    }

    /** 서비스 계정 RS256 JWT → OAuth2 access_token 교환. 만료 5분 전까지 캐시. */
    private synchronized String currentAccessToken() {
        Instant now = Instant.now();
        if (cachedToken != null && tokenExpiry != null && now.isBefore(tokenExpiry.minus(TOKEN_MARGIN))) {
            return cachedToken;
        }
        try {
            String jwt = Jwts.builder()
                    .issuer(clientEmail)
                    .subject(clientEmail)
                    // aud 는 반드시 단일 "문자열" — 구글 토큰엔드포인트는 배열 aud 를 거부("Failed audience check").
                    // JJWT 0.12.3 의 .audience().add() 는 단일값도 배열로 직렬화 → 0.12.4+ 의 .single() 사용.
                    .audience().single(TOKEN_URI)
                    .claim("scope", SCOPE)
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(Duration.ofHours(1))))
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();

            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                    + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URI))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    // read 타임아웃 — 구글 OAuth2 토큰엔드포인트 지연 시 무한 대기 방지.
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FCM token 교환 실패 status={} body={}", resp.statusCode(), abbrev(resp.body()));
                return null;
            }
            JsonNode json = mapper.readTree(resp.body());
            cachedToken = json.path("access_token").asText(null);
            long expiresIn = json.path("expires_in").asLong(3600);
            tokenExpiry = now.plusSeconds(expiresIn);
            return cachedToken;
        } catch (Exception e) {
            log.warn("FCM token 교환 error : {}", e.getMessage());
            return null;
        }
    }

    private String parseFcmErrorCode(String body) {
        try {
            JsonNode err = mapper.readTree(body).path("error");
            JsonNode details = err.path("details");
            if (details.isArray()) {
                for (JsonNode d : details) {
                    String ec = d.path("errorCode").asText("");
                    if (!ec.isBlank()) return ec;
                }
            }
            return err.path("status").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}

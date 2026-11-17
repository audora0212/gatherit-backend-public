package com.gatherit.api.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Expo Push Service HTTP 호출.
 * https://docs.expo.dev/push-notifications/sending-notifications/
 *
 * - endpoint: POST https://exp.host/--/api/v2/push/send
 * - body: 단일 메시지 또는 array (≤100). 우리는 단일 사용.
 * - 응답: { "data": [ { "status": "ok"|"error", ... } ] }
 *
 * iOS 의 APNs 키는 EAS 서버에 등록돼있어 Expo Push 가 자동 사용 (별도 셋업 불필요).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpoPushService {

    private static final String ENDPOINT = "https://exp.host/--/api/v2/push/send";

    private final UserRepository userRepository;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://exp.host")
            .build();

    /**
     * 사용자에게 푸시 알림 전송 — token 없으면 silent skip.
     * @param userId 대상
     * @param title 표시 제목
     * @param body 본문 (subtitle)
     * @param data 앱이 받을 추가 payload (deep link routing 등)
     */
    @Async
    public void sendToUser(Long userId, String title, String body, Map<String, Object> data) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;
            String token = user.getExpoPushToken();
            if (token == null || token.isBlank()) return;

            Map<String, Object> message = Map.of(
                    "to", token,
                    "title", title != null ? title : "GatherIt",
                    "body", body != null ? body : "",
                    "sound", "default",
                    "data", data != null ? data : Map.of()
            );

            JsonNode resp = restClient.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .body(JsonNode.class);

            if (resp != null) {
                JsonNode results = resp.path("data");
                if (results.isArray()) {
                    for (JsonNode r : results) {
                        String status = r.path("status").asText("");
                        if (!"ok".equals(status)) {
                            String code = r.path("details").path("error").asText("");
                            log.warn("Expo push not delivered to user={} token={} reason={} details={}",
                                    userId, abbrev(token), code, r);
                            // DeviceNotRegistered: 디바이스 권한 회수/앱 삭제 → 토큰 제거
                            if ("DeviceNotRegistered".equals(code)) {
                                user.updateExpoPushToken(null);
                                userRepository.save(user);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Expo push send failed user={} : {}", userId, e.getMessage());
        }
    }

    public void sendToUsers(List<Long> userIds, String title, String body, Map<String, Object> data) {
        if (userIds == null || userIds.isEmpty()) return;
        for (Long uid : userIds) sendToUser(uid, title, body, data);
    }

    private String abbrev(String token) {
        if (token == null) return "null";
        if (token.length() < 20) return token;
        return token.substring(0, 12) + "...";
    }
}

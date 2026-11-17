package com.gatherit.api.auth.service;

import com.gatherit.api.auth.dto.KakaoTokenResponse;
import com.gatherit.api.auth.dto.KakaoUserInfo;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 카카오 OAuth REST 호출 — 토큰 교환 + 사용자 정보 조회.
 * client-secret 은 서버에서만 보유 (FE 노출 X).
 */
@Slf4j
@Component
public class KakaoOAuthClient {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URI = "https://kapi.kakao.com/v2/user/me";

    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;

    public KakaoOAuthClient(
            @Value("${kakao.client-id}") String clientId,
            @Value("${kakao.client-secret:}") String clientSecret
    ) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.restClient = buildRest();
    }

    // 타임아웃 명시 — 카카오 API 지연 시 로그인 요청 스레드가 무한 대기(read 무제한)하는 것 방지.
    // 기본 RestClient.builder().build() 는 read 타임아웃이 무제한이라, 카카오 점검/지연 시
    // 로그인 스레드가 영영 매달려 누적되면 서비스가 마비된다(엣지 감사 P0-2). connect 3s / read 10s.
    private static RestClient buildRest() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(10000);
        return RestClient.builder().requestFactory(f).build();
    }

    /** Authorization Code → Access Token 교환. */
    public KakaoTokenResponse exchangeToken(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.add("client_secret", clientSecret);
        }
        form.add("redirect_uri", redirectUri);
        form.add("code", code);

        try {
            return restClient.post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
        } catch (Exception e) {
            log.error("Kakao token exchange failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }

    /** Access Token 으로 카카오 사용자 정보 조회. */
    public KakaoUserInfo fetchUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(USER_INFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(KakaoUserInfo.class);
        } catch (Exception e) {
            log.error("Kakao user info fetch failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.KAKAO_AUTH_FAILED);
        }
    }
}

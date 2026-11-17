package com.gatherit.api.auth.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.gatherit.api.auth.dto.GoogleUserInfo;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Google Sign In idToken 검증.
 * 앱이 expo-auth-session/providers/google 로 받은 JWT 를 BE 에 전달 → 여기서 검증.
 *
 * - issuer: https://accounts.google.com (또는 accounts.google.com 변종)
 * - audience: Google Cloud Console 의 OAuth client ID (Web/iOS 각각 등록 가능 → 콤마 구분 다중 허용)
 * - JWKS: https://www.googleapis.com/oauth2/v3/certs
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final OidcJwtVerifier verifier;

    /** 콤마로 구분된 여러 client ID 허용 (iOS, Android, Web 등). 미설정 시 audience 검증 skip. */
    @Value("${google.client-ids:}")
    private String clientIdsCsv;

    public GoogleUserInfo verifyIdToken(String idToken) {
        try {
            Set<String> audiences = StringUtils.hasText(clientIdsCsv)
                    ? Arrays.stream(clientIdsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(HashSet::new))
                    : Set.of();
            DecodedJWT jwt = verifier.verify(idToken, JWKS_URL, ISSUERS, audiences);
            String sub = jwt.getSubject();
            String email = jwt.getClaim("email").asString();
            Boolean emailVerified = jwt.getClaim("email_verified").asBoolean();
            String name = jwt.getClaim("name").asString();
            String picture = jwt.getClaim("picture").asString();
            if (sub == null || sub.isBlank()) {
                throw new IllegalStateException("missing sub claim");
            }
            return new GoogleUserInfo(sub, email, emailVerified, name, picture);
        } catch (Exception e) {
            log.error("Google idToken verify failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }
}

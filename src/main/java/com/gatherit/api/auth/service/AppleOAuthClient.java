package com.gatherit.api.auth.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.gatherit.api.auth.dto.AppleUserInfo;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Apple Sign In identityToken 검증.
 * 앱이 expo-apple-authentication 으로 받은 JWT 를 그대로 BE 에 전달 → 여기서 검증.
 *
 * - issuer: https://appleid.apple.com
 * - audience: 앱의 Bundle ID (com.audora.gatherit)
 * - JWKS: https://appleid.apple.com/auth/keys
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppleOAuthClient {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final Set<String> ISSUERS = Set.of("https://appleid.apple.com");

    private final OidcJwtVerifier verifier;

    @Value("${apple.bundle-id:com.audora.gatherit}")
    private String bundleId;

    public AppleUserInfo verifyIdentityToken(String identityToken) {
        try {
            DecodedJWT jwt = verifier.verify(identityToken, JWKS_URL, ISSUERS, Set.of(bundleId));
            String sub = jwt.getSubject();
            String email = jwt.getClaim("email").asString();
            Boolean emailVerified = jwt.getClaim("email_verified").asBoolean();
            if (sub == null || sub.isBlank()) {
                throw new IllegalStateException("missing sub claim");
            }
            return new AppleUserInfo(sub, email, emailVerified);
        } catch (Exception e) {
            log.error("Apple identityToken verify failed: {}", e.getMessage());
            throw new CustomException(ErrorCode.APPLE_AUTH_FAILED);
        }
    }
}

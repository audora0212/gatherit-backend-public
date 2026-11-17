package com.gatherit.api.auth.service;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OIDC identityToken 공용 검증기 — Apple / Google.
 * provider 의 JWKS endpoint 에서 공개키 가져와 RSA 서명 검증 + issuer + audience 매치.
 *
 * JWKS provider 인스턴스는 cached / rate-limited (라이브러리 기본).
 */
@Slf4j
@Component
public class OidcJwtVerifier {

    /** issuer 별 JwkProvider 캐시 — 동일 provider 호출은 재사용. */
    private final ConcurrentHashMap<String, JwkProvider> providerCache = new ConcurrentHashMap<>();

    /**
     * @param token JWT (signed)
     * @param jwksUrl provider 의 공개키 endpoint
     * @param expectedIssuer iss claim 매치 대상 (또는 여러 후보)
     * @param allowedAudiences aud claim 후보 — 하나라도 일치하면 통과
     * @return DecodedJWT (claim 추출용)
     */
    public DecodedJWT verify(String token, String jwksUrl, Set<String> expectedIssuers, Set<String> allowedAudiences) throws Exception {
        DecodedJWT decoded = JWT.decode(token);
        String kid = decoded.getKeyId();
        if (kid == null) {
            throw new IllegalArgumentException("missing kid in JWT header");
        }

        JwkProvider provider = providerCache.computeIfAbsent(jwksUrl, url -> {
            try {
                return new JwkProviderBuilder(new URL(url))
                        .cached(10, 24, TimeUnit.HOURS)
                        .rateLimited(20, 1, TimeUnit.MINUTES)
                        // 타임아웃 명시 — 캐시 미스 시 JWKS fetch 가 무한 대기(URLConnection 기본=무제한)하면
                        // Apple/Google 로그인 스레드가 영영 매달린다. connect 3s / read 10s (엣지 감사 P0-2 동반).
                        .timeouts(3000, 10000)
                        .build();
            } catch (Exception e) {
                throw new RuntimeException("invalid JWKS URL: " + url, e);
            }
        });

        Jwk jwk = provider.get(kid);
        Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);

        // fluent chain — clock skew 60s, issuer 다중 후보, audience OR 매치 (withAnyOfAudience)
        Verification verification = JWT.require(algorithm).acceptLeeway(60);
        if (!expectedIssuers.isEmpty()) {
            verification = verification.withIssuer(expectedIssuers.toArray(new String[0]));
        }
        if (!allowedAudiences.isEmpty()) {
            verification = verification.withAnyOfAudience(allowedAudiences.toArray(new String[0]));
        }
        return verification.build().verify(token);
    }
}

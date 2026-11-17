package com.gatherit.api.security;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtils 단위 테스트 — 실제 jjwt 라이브러리 사용, 외부 의존 없음.
 * ReflectionTestUtils 로 @Value 필드 직접 주입 후 @PostConstruct(init) 수동 호출.
 */
class JwtUtilsTest {

    // HS256 최소 32바이트(256bit) 이상이어야 함
    private static final String TEST_SECRET =
            "test-secret-key-must-be-at-least-32-bytes!!";
    private static final long ACCESS_EXPIRATION  = 3_600_000L;   // 1시간
    private static final long REFRESH_EXPIRATION = 604_800_000L; // 7일

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtUtils, "expiration", ACCESS_EXPIRATION);
        ReflectionTestUtils.setField(jwtUtils, "refreshExpiration", REFRESH_EXPIRATION);
        jwtUtils.init(); // @PostConstruct 수동 호출 → key 초기화
    }

    // ── 1. generateAccessToken / getUserId round-trip ────────────────────────

    @Test
    @DisplayName("generateAccessToken → getUserId round-trip")
    void accessToken_userIdRoundTrip() {
        Long userId = 42L;
        String token = jwtUtils.generateAccessToken(userId, "user@example.com");
        assertThat(jwtUtils.getUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("generateAccessToken → getEmail round-trip")
    void accessToken_emailRoundTrip() {
        String email = "test@gatherit.io";
        String token = jwtUtils.generateAccessToken(99L, email);
        assertThat(jwtUtils.getEmail(token)).isEqualTo(email);
    }

    // ── 2. generateRefreshToken round-trip ───────────────────────────────────

    @Test
    @DisplayName("generateRefreshToken → getUserId round-trip")
    void refreshToken_userIdRoundTrip() {
        Long userId = 7L;
        String token = jwtUtils.generateRefreshToken(userId);
        assertThat(jwtUtils.getUserId(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("refreshToken email 클레임 없으면 getEmail null 반환")
    void refreshToken_emailIsNull() {
        String token = jwtUtils.generateRefreshToken(7L);
        assertThat(jwtUtils.getEmail(token)).isNull();
    }

    // ── 3. validateToken — 정상 토큰 ─────────────────────────────────────────

    @Test
    @DisplayName("validateToken — 유효한 토큰은 true")
    void validateToken_valid_true() {
        String token = jwtUtils.generateAccessToken(1L, "a@b.com");
        assertThat(jwtUtils.validateToken(token)).isTrue();
    }

    // ── 4. validateToken — 만료 토큰 ─────────────────────────────────────────

    @Test
    @DisplayName("validateToken — 만료 토큰은 EXPIRED_TOKEN 예외")
    void validateToken_expired_throwsExpiredToken() {
        // expiration 을 -1ms 로 교체 → 즉시 만료 토큰 생성
        ReflectionTestUtils.setField(jwtUtils, "expiration", -1L);
        String expiredToken = jwtUtils.generateAccessToken(1L, "a@b.com");

        // 원래 expiration 복원(필요 없지만 명시)
        ReflectionTestUtils.setField(jwtUtils, "expiration", ACCESS_EXPIRATION);

        assertThatThrownBy(() -> jwtUtils.validateToken(expiredToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EXPIRED_TOKEN);
    }

    // ── 5. validateToken — 위조 토큰 ─────────────────────────────────────────

    @Test
    @DisplayName("validateToken — 서명 위조 토큰은 INVALID_TOKEN 예외")
    void validateToken_tamperedSignature_throwsInvalidToken() {
        String token = jwtUtils.generateAccessToken(1L, "a@b.com");
        // 마지막 서명 부분 변조
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "INVALIDSIGNATURE";

        assertThatThrownBy(() -> jwtUtils.validateToken(tampered))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("validateToken — 완전히 잘못된 문자열은 INVALID_TOKEN 예외")
    void validateToken_garbage_throwsInvalidToken() {
        assertThatThrownBy(() -> jwtUtils.validateToken("not.a.jwt"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("validateToken — 빈 문자열은 INVALID_TOKEN 예외")
    void validateToken_blank_throwsInvalidToken() {
        assertThatThrownBy(() -> jwtUtils.validateToken(""))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    // ── 6. 다른 시크릿 키로 서명된 토큰 ─────────────────────────────────────

    @Test
    @DisplayName("다른 시크릿으로 서명된 토큰은 INVALID_TOKEN 예외")
    void validateToken_differentSecret_throwsInvalidToken() {
        // 다른 JwtUtils 인스턴스로 다른 키 서명
        JwtUtils other = new JwtUtils();
        ReflectionTestUtils.setField(other, "secret", "another-secret-key-must-be-32bytes!!");
        ReflectionTestUtils.setField(other, "expiration", ACCESS_EXPIRATION);
        ReflectionTestUtils.setField(other, "refreshExpiration", REFRESH_EXPIRATION);
        other.init();

        String foreignToken = other.generateAccessToken(1L, "a@b.com");

        assertThatThrownBy(() -> jwtUtils.validateToken(foreignToken))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    // ── 7. 페이로드 위변조 (header/payload 교체 + 원본 서명 재사용) ───────────

    @Test
    @DisplayName("payload 위조(다른 userId) + 원본 서명 조합은 INVALID_TOKEN 예외")
    void validateToken_payloadTampered_throwsInvalidToken() {
        String tokenA = jwtUtils.generateAccessToken(1L, "a@b.com");
        String tokenB = jwtUtils.generateAccessToken(2L, "b@b.com");

        // tokenB 의 payload 를 tokenA 의 서명과 결합 → 위조
        String[] partsA = tokenA.split("\\.");
        String[] partsB = tokenB.split("\\.");
        String forged = partsA[0] + "." + partsB[1] + "." + partsA[2];

        assertThatThrownBy(() -> jwtUtils.validateToken(forged))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void accessAndRefreshAreNotInterchangeable() {
        String access = jwtUtils.generateAccessToken(1L, null);
        String refresh = jwtUtils.generateRefreshToken(1L);
        assertThat(jwtUtils.validateAccessToken(access)).isTrue();
        assertThat(jwtUtils.validateRefreshToken(refresh)).isTrue();
        assertInvalid(() -> jwtUtils.validateAccessToken(refresh));
        assertInvalid(() -> jwtUtils.validateRefreshToken(access));
    }

    @Test
    void issuingTwiceInTheSameSecondProducesDifferentRefreshTokens() {
        assertThat(jwtUtils.generateRefreshToken(1L)).isNotEqualTo(jwtUtils.generateRefreshToken(1L));
    }

    @Test
    void legacyAccessWithAndWithoutEmailRemainsValid() {
        for (String email : new String[]{"user@example.com", null}) {
            Map<String, Object> claims = new HashMap<>();
            claims.put("email", email); // jjwt omits null values in the old generation path.
            String token = legacyToken(claims, ACCESS_EXPIRATION);
            assertThat(jwtUtils.validateAccessToken(token)).isTrue();
            assertInvalid(() -> jwtUtils.validateRefreshToken(token));
        }
    }

    @Test
    void legacyRefreshIsAcceptedOnlyAtRefreshEndpoint() {
        String token = legacyToken(Map.of(), REFRESH_EXPIRATION);
        assertThat(jwtUtils.validateRefreshToken(token)).isTrue();
        assertInvalid(() -> jwtUtils.validateAccessToken(token));
    }

    @Test
    void explicitUnknownPurposeCannotFallBackToLegacy() {
        String token = legacyToken(Map.of("token_use", "something-else"), ACCESS_EXPIRATION);
        assertInvalid(() -> jwtUtils.validateAccessToken(token));
        assertInvalid(() -> jwtUtils.validateRefreshToken(token));
    }

    @Test
    void legacyAccessLifetimeHasAnExplicitOneHourBoundary() {
        assertThat(jwtUtils.validateAccessToken(legacyToken(Map.of(), ACCESS_EXPIRATION))).isTrue();
        assertInvalid(() -> jwtUtils.validateAccessToken(legacyToken(Map.of(), ACCESS_EXPIRATION + 1_000)));
        assertInvalid(() -> jwtUtils.validateAccessToken(legacyToken(Map.of(), REFRESH_EXPIRATION)));
    }

    @Test
    void tokensWithoutIssuedAtCannotEnterLegacyCompatibility() {
        String token = Jwts.builder().subject("1").expiration(new Date(System.currentTimeMillis() + ACCESS_EXPIRATION))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertInvalid(() -> jwtUtils.validateAccessToken(token));
        assertInvalid(() -> jwtUtils.validateRefreshToken(token));
    }

    @Test
    void futureIssuedAtBeyondThirtySecondClockToleranceIsRejected() {
        long future = System.currentTimeMillis() + 60_000;
        String token = Jwts.builder().subject("1").issuedAt(new Date(future))
                .expiration(new Date(future + ACCESS_EXPIRATION))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertInvalid(() -> jwtUtils.validateAccessToken(token));
    }

    @Test
    void malformedSubjectIsInvalidInsteadOfBecomingAnUnhandledFilterException() {
        long now = System.currentTimeMillis();
        String token = Jwts.builder().subject("not-a-user-id").claim("token_use", "access")
                .issuedAt(new Date(now)).expiration(new Date(now + ACCESS_EXPIRATION))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        assertInvalid(() -> jwtUtils.validateAccessToken(token));
    }

    private String legacyToken(Map<String, Object> claims, long ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder().claims(claims).subject("1")
                .issuedAt(new Date(now)).expiration(new Date(now + ttl))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }
}

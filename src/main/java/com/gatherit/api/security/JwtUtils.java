package com.gatherit.api.security;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * JWT 발급/검증 유틸 (jjwt 0.12.x). Access와 refresh 용도를 구분한다.
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    @Value("${jwt.refresh-expiration:604800000}")
    private Long refreshExpiration;

    private SecretKey key;

    @PostConstruct
    public void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Access token — subject = userId, token_use = access, optional email. */
    public String generateAccessToken(Long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("token_use", "access");
        return createToken(claims, String.valueOf(userId), expiration);
    }

    /** Refresh token — token_use = refresh; unique jti on every issuance. */
    public String generateRefreshToken(Long userId) {
        return createToken(Map.of("token_use", "refresh"), String.valueOf(userId), refreshExpiration);
    }

    private String createToken(Map<String, Object> claims, String subject, long ttlMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttlMs);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(key)
                .compact();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String getEmail(String token) {
        Object email = parseClaims(token).get("email");
        return email == null ? null : email.toString();
    }

    public Instant getExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public boolean validateToken(String token) {
        return validateAccessToken(token);
    }

    public boolean validateAccessToken(String token) {
        return validatePurpose(token, "access");
    }

    public boolean validateRefreshToken(String token) {
        return validatePurpose(token, "refresh");
    }

    private boolean validatePurpose(String token, String expectedUse) {
        try {
            Claims claims = parseClaims(token);
            if (claims.getSubject() == null || Long.parseLong(claims.getSubject()) <= 0
                    || claims.getIssuedAt() == null || claims.getExpiration() == null
                    || claims.getIssuedAt().after(new Date(System.currentTimeMillis() + 30_000))
                    || !claims.getExpiration().after(claims.getIssuedAt())) {
                throw new CustomException(ErrorCode.INVALID_TOKEN);
            }
            Object tokenUse = claims.get("token_use");
            if (tokenUse != null) {
                if (!expectedUse.equals(tokenUse)) {
                    throw new CustomException(ErrorCode.INVALID_TOKEN);
                }
            } else {
                // Legacy access tokens can have no email (Apple/Kakao email not supplied).
                // The signed lifetime distinguishes the existing 1h access from 365d refresh.
                // Do not require a new claim on sessions issued before this deployment.
                long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
                boolean legacyAccess = lifetime > 0 && lifetime <= expiration && lifetime < refreshExpiration;
                boolean legacyRefresh = lifetime > expiration && !claims.containsKey("email");
                if (!("access".equals(expectedUse) ? legacyAccess : legacyRefresh)) {
                    throw new CustomException(ErrorCode.INVALID_TOKEN);
                }
            }
            return true;
        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

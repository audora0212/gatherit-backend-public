package com.gatherit.api.auth.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 사용자별 활성 refresh token — 1 user 1 active token (PK = userId).
 *
 * MySQL 영속화로 BE 재시작에도 세션 유지. 매 refresh 마다 token + expiresAt 슬라이딩 갱신.
 * 분산 환경에선 동일 DB 를 여러 BE 인스턴스가 보면 자동 동기화 (별도 캐시 layer 불필요).
 */
@Entity
@Getter
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 1024)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * 직전 토큰 — 회전 직후 grace 창 동안만 유효(응답 유실로 새 토큰을 못 받은 합법 클라의 재시도 구제용).
     * grace 안에 직전 토큰이 다시 와도 탈취로 간주하지 않고 현재 세션을 돌려준다.
     */
    @Column(name = "previous_token", length = 1024)
    private String previousToken;

    @Column(name = "previous_token_expires_at")
    private Instant previousTokenExpiresAt;

    @Column(name = "recovery_key_hash", length = 64)
    private String recoveryKeyHash;

    @Column(name = "recovery_expires_at")
    private Instant recoveryExpiresAt;

    @Builder
    private RefreshToken(Long userId, String token, Instant expiresAt) {
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    /**
     * 회전 — 직전 token 을 grace 창까지 유효 보존 후 새 token + expiresAt 슬라이딩 갱신.
     * @param previousGraceUntil 직전 토큰을 이 시각까지 grace 로 허용
     */
    public void rotate(String token, Instant expiresAt, Instant previousGraceUntil) {
        this.previousToken = this.token;            // 회전 직전의 current 를 grace 로 내림
        this.previousTokenExpiresAt = previousGraceUntil;
        this.token = token;
        this.expiresAt = expiresAt;
        this.recoveryKeyHash = null;
        this.recoveryExpiresAt = null;
    }

    /** A fresh login replaces the session; the prior login must not recover into the new one. */
    public void replaceSession(String token, Instant expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
        this.previousToken = null;
        this.previousTokenExpiresAt = null;
        this.recoveryKeyHash = null;
        this.recoveryExpiresAt = null;
    }

    public void enableRecovery(String recoveryKeyHash, Instant recoveryExpiresAt) {
        this.recoveryKeyHash = recoveryKeyHash;
        this.recoveryExpiresAt = recoveryExpiresAt;
    }

    public boolean matchesRecovery(String candidate, String candidateHash, Instant now) {
        return candidateHash != null && recoveryKeyHash != null
                && recoveryExpiresAt != null && recoveryExpiresAt.isAfter(now)
                && expiresAt.isAfter(now) && candidate.equals(previousToken)
                && java.security.MessageDigest.isEqual(
                        recoveryKeyHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        candidateHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** 직전 토큰이 grace 창 안에서 이 토큰과 일치하는가 (응답 유실 재시도 구제). */
    public boolean matchesPreviousWithinGrace(String candidate, Instant now) {
        return expiresAt.isAfter(now)
                && previousToken != null
                && previousTokenExpiresAt != null
                && previousTokenExpiresAt.isAfter(now)
                && previousToken.equals(candidate);
    }
}

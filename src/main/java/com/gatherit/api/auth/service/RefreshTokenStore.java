package com.gatherit.api.auth.service;

import com.gatherit.api.auth.entity.RefreshToken;
import com.gatherit.api.auth.repository.RefreshTokenRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/** Persistent single-session storage. All session writers lock user then refresh row. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {
    private static final long MAX_RECOVERY_MS = 7L * 24 * 60 * 60 * 1000;

    private final RefreshTokenRepository repository;
    private final EntityManager entityManager;
    private final JwtUtils jwtUtils;
    private Clock clock = Clock.systemUTC();

    @Value("${jwt.refresh-grace-ms:60000}")
    private long refreshGraceMs;

    @Value("${jwt.refresh-recovery-ms:604800000}")
    private long refreshRecoveryMs;

    public record RotationResult(String token, String outcome) {}

    /** Explicit login: replace the one active session and discard all prior recovery proofs. */
    @Transactional
    public void save(Long userId, String token) {
        lockUser(userId);
        RefreshToken rt = lockedToken(userId);
        Instant expiresAt = jwtUtils.getExpiration(token);
        if (rt == null) {
            repository.save(RefreshToken.builder().userId(userId).token(token).expiresAt(expiresAt).build());
        } else {
            rt.replaceSession(token, expiresAt);
        }
    }

    /** Validation, rotation and retry recovery happen under one database lock and transaction. */
    @Transactional
    public RotationResult refresh(Long userId, String presented, String attemptId) {
        if (attemptId != null && !attemptId.matches("[0-9a-f]{64}")) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        lockUser(userId);
        RefreshToken rt = lockedToken(userId);
        Instant now = clock.instant();
        if (rt == null || !rt.getExpiresAt().isAfter(now)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        if (rt.getToken().equals(presented)) {
            String next = jwtUtils.generateRefreshToken(userId);
            Instant expiresAt = jwtUtils.getExpiration(next);
            rt.rotate(next, expiresAt, now.plusMillis(refreshGraceMs));
            if (attemptId != null) {
                // Bind the proof to the exact predecessor, not only its request ID.
                // An old server changing previous_token cannot reuse a stale recovery key.
                Instant until = now.plusMillis(Math.max(0, Math.min(refreshRecoveryMs, MAX_RECOVERY_MS)));
                Instant sourceExpiry = jwtUtils.getExpiration(presented);
                if (sourceExpiry.isBefore(until)) until = sourceExpiry;
                if (expiresAt.isBefore(until)) until = expiresAt;
                rt.enableRecovery(recoveryHash(presented, attemptId), until);
            }
            return new RotationResult(next, "rotated");
        }
        if (rt.matchesRecovery(presented, recoveryHash(presented, attemptId), now)) {
            return new RotationResult(rt.getToken(), "attempt_recovered");
        }
        if (rt.matchesPreviousWithinGrace(presented, now)) {
            return new RotationResult(rt.getToken(), "grace_recovered");
        }
        // A stale request must not destroy another device's newer session. Previously invalidate()
        // was followed by a RuntimeException and rolled back anyway; rejection is now explicit.
        throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    @Transactional
    public void invalidate(Long userId) {
        lockUser(userId);
        RefreshToken rt = lockedToken(userId);
        if (rt != null) repository.delete(rt);
    }

    /** A delayed logout may only revoke the session identified by its original credential snapshot. */
    @Transactional
    public boolean invalidateMatching(Long userId, String presented, String attemptId) {
        if (presented == null || presented.isBlank()
                || (attemptId != null && !attemptId.matches("[0-9a-f]{64}"))) {
            return false;
        }
        lockUser(userId);
        RefreshToken rt = lockedToken(userId);
        if (rt == null) return false;
        Instant now = clock.instant();
        boolean matches = rt.getToken().equals(presented)
                || rt.matchesPreviousWithinGrace(presented, now)
                || rt.matchesRecovery(presented, recoveryHash(presented, attemptId), now);
        if (!matches) return false;
        repository.delete(rt);
        return true;
    }

    private void lockUser(Long userId) {
        // Lock the parent even if there is no refresh row yet (two simultaneous logins).
        if (entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE) == null) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
    }

    private RefreshToken lockedToken(Long userId) {
        // Locking read sees the latest committed row under MySQL REPEATABLE READ.
        return entityManager.find(RefreshToken.class, userId, LockModeType.PESSIMISTIC_WRITE);
    }

    private String recoveryHash(String token, String attemptId) {
        if (attemptId == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token + "\n" + attemptId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant());
        if (deleted > 0) log.info("Cleaned up {} expired refresh tokens", deleted);
    }
}

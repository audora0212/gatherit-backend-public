package com.gatherit.api.group.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 그룹 초대 토큰 — Caffeine 인메모리 TTL.
 * key: token → groupId. 다회용 (TTL 내 N명 가입 가능, 카톡 단톡방 패턴).
 *
 * 토큰 포맷: 8자 uppercase. 0/1/O/I 등 헷갈리는 문자 제외 → 사람이 읽고 입력 가능.
 *  - URL 사용: /invite/AB3KX9JT
 *  - UI 표시: AB3K-X9JT (4자씩 split)
 *  - 사용자 입력: 어느 형식이든 받음 (validate() 가 정규화)
 *
 * NOTE: 단일 인스턴스 전제. 다중 인스턴스 전환 시 Redis 어댑터로 교체.
 *       프로세스 재시작 시 발급된 토큰 모두 무효 — 초기 단계 trade-off.
 */
@Component
public class InviteTokenStore {

    /** 헷갈리는 문자(0/O/1/I/L) 제외 — 32자 알파벳. */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom rng = new SecureRandom();
    private final Cache<String, Long> cache;
    private final long ttlSeconds;

    public InviteTokenStore(@Value("${gatherit.invite.ttl-seconds:604800}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(100_000)
                .build();
    }

    public Issued issue(Long groupId) {
        // 충돌 시 재시도 — 32^8 ≈ 1조 조합이라 사실상 불필요하지만 안전망.
        for (int attempt = 0; attempt < 5; attempt++) {
            String token = generateCode();
            if (cache.getIfPresent(token) == null) {
                cache.put(token, groupId);
                return new Issued(token, Instant.now().plusSeconds(ttlSeconds));
            }
        }
        // 5회 충돌은 사실상 불가능 — 발생 시 운영 알림 대상
        throw new IllegalStateException("Failed to generate unique invite token after 5 attempts");
    }

    /**
     * 토큰 검증 — 유효하면 groupId 반환. 토큰을 소비하지 않음 (다회용).
     * 입력 정규화: 하이픈 제거, 공백 제거, uppercase.
     */
    public Optional<Long> validate(String token) {
        if (token == null) return Optional.empty();
        String normalized = token.replace("-", "").replace(" ", "").toUpperCase();
        Long groupId = cache.getIfPresent(normalized);
        return Optional.ofNullable(groupId);
    }

    /** 토큰 명시적 폐기 (링크 유출 대응). */
    public void revoke(String token) {
        if (token == null) return;
        String normalized = token.replace("-", "").replace(" ", "").toUpperCase();
        cache.invalidate(normalized);
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(rng.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public record Issued(String token, Instant expiresAt) {}
}

package com.gatherit.api.auth.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "폰 단일 기기인데 로그아웃" 가설 재현 — refresh 토큰 회전(rotate) 메커니즘 단위검증.
 *
 * 배경(AuthService.refresh 의 판정):
 *   refresh 토큰 t 로 갱신 시도 →
 *     (1) t == 현재 토큰      → 정상 회전
 *     (2) t == 직전 토큰 & grace 내 → 응답유실 재시도로 보고 복구(회전 없음)
 *     (3) 둘 다 아님           → invalidate + INVALID_TOKEN  ＝ 강제 로그아웃
 *
 * 핵심 약점: 직전 토큰은 **단 1칸**, grace 는 60초.
 *   → 토큰이 2번 회전되거나(중간 회전이 끼면), 재시도가 grace 밖이면 (3)에 빠져 로그아웃.
 * 이 테스트는 그 (3) 조건을 동시성/sleep 없이 회전 순서만으로 결정적으로 재현한다.
 */
class RefreshTokenRotationTest {

    private static final long UID = 34L;            // 기존 사용자 시나리오
    private Instant future() { return Instant.now().plusSeconds(31_536_000L); } // 365d

    private RefreshToken login(String token) {
        return RefreshToken.builder().userId(UID).token(token).expiresAt(future()).build();
    }

    // AuthService.refresh 가 로그아웃(case 3)으로 가는 조건을 그대로 평가
    private boolean wouldForceLogout(RefreshToken rt, String presented, Instant now) {
        boolean isCurrent = rt.getToken().equals(presented) && rt.getExpiresAt().isAfter(now);
        boolean withinGrace = rt.matchesPreviousWithinGrace(presented, now);
        return !isCurrent && !withinGrace;
    }

    @Test
    @DisplayName("정상: 현재 토큰으로 refresh → 로그아웃 아님")
    void currentToken_noLogout() {
        Instant now = Instant.now();
        RefreshToken rt = login("T1");
        assertThat(wouldForceLogout(rt, "T1", now)).isFalse();
    }

    @Test
    @DisplayName("[제어군] 단일 회전 + grace 내 직전 토큰 재시도 → 복구(로그아웃 아님)")
    void singleRotation_withinGrace_recovers() {
        Instant now = Instant.now();
        RefreshToken rt = login("T1");
        rt.rotate("T2", future(), now.plusSeconds(60));   // T1 → grace(60s)

        // 응답 유실로 옛 T1 을 grace 안에 다시 제출 → 복구돼야 함(로그아웃 X)
        assertThat(rt.matchesPreviousWithinGrace("T1", now)).isTrue();
        assertThat(wouldForceLogout(rt, "T1", now)).isFalse();
    }

    @Test
    @DisplayName("[재현 A] 2번 회전되면 그 사이 옛 토큰을 든 요청은 강제 로그아웃 — 직전 토큰 1칸뿐")
    void doubleRotation_evictsOriginal_forcesLogout() {
        Instant now = Instant.now();
        RefreshToken rt = login("T1");

        rt.rotate("T2", future(), now.plusSeconds(60));   // prev = T1 (grace)
        assertThat(rt.matchesPreviousWithinGrace("T1", now)).isTrue();   // 아직 T1 복구 가능

        rt.rotate("T3", future(), now.plusSeconds(60));   // prev = T2 → T1 은 밀려남(evicted)

        assertThat(rt.getToken()).isEqualTo("T3");
        assertThat(rt.matchesPreviousWithinGrace("T2", now)).isTrue();   // T2 는 복구 가능
        // T1 은 현재도 아니고 직전(grace)도 아님 → 강제 로그아웃
        assertThat(rt.matchesPreviousWithinGrace("T1", now)).isFalse();
        assertThat(wouldForceLogout(rt, "T1", now)).isTrue();            // ← 폰이 옛 T1 들고 오면 로그아웃
    }

    @Test
    @DisplayName("[재현 B] 회전 후 60초(grace) 지나 옛 토큰 재시도 → 강제 로그아웃")
    void graceExpired_staleToken_forcesLogout() {
        Instant now = Instant.now();
        RefreshToken rt = login("T1");
        rt.rotate("T2", future(), now.plusSeconds(60));   // T1 grace until now+60s

        assertThat(wouldForceLogout(rt, "T1", now)).isFalse();           // grace 내: 복구
        assertThat(wouldForceLogout(rt, "T1", now.plusSeconds(61))).isTrue(); // grace 밖: 로그아웃
    }
}

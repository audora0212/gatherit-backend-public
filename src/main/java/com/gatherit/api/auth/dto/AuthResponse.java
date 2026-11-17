package com.gatherit.api.auth.dto;

import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.UserRole;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserSummary user,
        /** 이번 인증으로 계정이 처음 생성됐는지 — 신규면 클라가 튜토리얼/권한 화면으로 라우팅. */
        boolean isNewUser
) {
    public record UserSummary(
            Long id,
            String email,
            String nickname,
            String profileImageUrl,
            OauthProvider oauthProvider,
            /** 개인 색상(#RRGGBB) — 로그인/가입 직후 클라가 프로필 색을 정확히 알도록 포함. null 허용. */
            String personalColor,
            /** 기존 앱 응답 형식을 유지하는 계정 역할 값. */
            UserRole role
    ) {}
}

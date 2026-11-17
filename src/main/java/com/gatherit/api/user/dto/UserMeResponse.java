package com.gatherit.api.user.dto;

import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.entity.UserRole;

public record UserMeResponse(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        OauthProvider oauthProvider,
        /** 생성자 바 색 — #RRGGBB. null = 클라 해시 fallback. */
        String personalColor,
        /** 기존 앱 응답 형식을 유지하는 계정 역할 값. */
        UserRole role
) {
    public static UserMeResponse from(User user) {
        return new UserMeResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getOauthProvider(),
                user.getPersonalColor(),
                user.getRole()
        );
    }
}

package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 앱이 expo-apple-authentication 으로 받은 identityToken (JWT) 을 BE 로 전달.
 * BE 가 Apple JWKS 로 서명 검증 후 sub/email 추출.
 *
 * fullName 은 Apple 이 첫 로그인 시 한 번만 내려줌 — 이후 로그인엔 null.
 * → 가입 시점에만 닉네임 fallback 으로 사용.
 */
public record AppleLoginRequest(
        @NotBlank String identityToken,
        String fullName
) {}

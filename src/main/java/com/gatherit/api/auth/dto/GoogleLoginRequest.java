package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 앱이 Google Sign In 으로 받은 idToken (JWT) 을 BE 로 전달.
 * BE 가 Google JWKS 로 서명 검증 후 sub/email/name/picture 추출.
 */
public record GoogleLoginRequest(
        @NotBlank String idToken
) {}

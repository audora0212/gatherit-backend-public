package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * FE 가 카카오 인증 후 받은 code 를 BE 로 보낼 때 사용.
 * redirectUri 는 카카오 콘솔에 등록된 값과 정확히 일치해야 함 (kakao 가 검증).
 */
public record KakaoLoginRequest(
        @NotBlank String code,
        @NotBlank String redirectUri
) {}

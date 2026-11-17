package com.gatherit.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        /** 로그인용 ID — 영문/숫자/언더스코어 4~20자. */
        @NotBlank @Size(min = 4, max = 20)
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "아이디는 영문/숫자/언더스코어만 사용 가능합니다")
        String username,
        @NotBlank @Email String email,
        /** 신규 비밀번호 — 최대 100자 및 UTF-8 72바이트. 최소 길이 정책은 기존과 동일. */
        @Schema(description = "신규 비밀번호: 최대 100자 및 UTF-8 인코딩 기준 72바이트. 기존 계정의 로그인에는 이 신규 가입 제한을 적용하지 않습니다.")
        @NotBlank @Size(max = 100) String password,
        @NotBlank @Size(min = 1, max = 50) String nickname
) {}

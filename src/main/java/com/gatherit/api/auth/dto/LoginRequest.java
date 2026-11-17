package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        /** 로그인은 아이디(username) 로. 이메일 X. */
        @NotBlank String username,
        @NotBlank String password
) {}

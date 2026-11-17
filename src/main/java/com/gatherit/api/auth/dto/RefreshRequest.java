package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RefreshRequest(
        @NotBlank String refreshToken,
        @Pattern(regexp = "[0-9a-f]{64}") String refreshAttemptId
) {
    public RefreshRequest(String refreshToken) {
        this(refreshToken, null);
    }
}

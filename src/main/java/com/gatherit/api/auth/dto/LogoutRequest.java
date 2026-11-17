package com.gatherit.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Optional for legacy clients; new clients bind logout to the session they actually left. */
public record LogoutRequest(
        @NotBlank String refreshToken,
        @Pattern(regexp = "[0-9a-f]{64}") String refreshAttemptId
) {}

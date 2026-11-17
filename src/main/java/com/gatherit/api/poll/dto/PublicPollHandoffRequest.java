package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PublicPollHandoffRequest(
        @NotBlank
        @Pattern(regexp = "^[a-f0-9]{32}$")
        String participantToken
) {}

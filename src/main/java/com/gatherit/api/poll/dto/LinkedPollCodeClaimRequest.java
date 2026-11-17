package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LinkedPollCodeClaimRequest(
        @NotBlank
        @Pattern(regexp = "^[A-HJ-KM-NP-Z2-9]{4}-?[A-HJ-KM-NP-Z2-9]{4}$")
        String connectionCode
) {}

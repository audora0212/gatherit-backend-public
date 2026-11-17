package com.gatherit.api.group.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinRequest(
        @NotBlank String token
) {}

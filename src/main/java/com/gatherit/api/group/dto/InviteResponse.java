package com.gatherit.api.group.dto;

import java.time.Instant;

public record InviteResponse(
        String token,
        Instant expiresAt
) {}

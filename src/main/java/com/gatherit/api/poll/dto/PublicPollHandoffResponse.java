package com.gatherit.api.poll.dto;

import java.time.Instant;

public record PublicPollHandoffResponse(
        String appUrl,
        String connectionCode,
        Instant expiresAt
) {}

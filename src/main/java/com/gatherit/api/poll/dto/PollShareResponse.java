package com.gatherit.api.poll.dto;

import java.time.Instant;

public record PollShareResponse(String token, String url, Instant expiresAt) {}

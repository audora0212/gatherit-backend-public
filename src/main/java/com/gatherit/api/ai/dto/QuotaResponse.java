package com.gatherit.api.ai.dto;

import java.time.Instant;

public record QuotaResponse(int usedToday, int dailyLimit, Instant resetAt) {}

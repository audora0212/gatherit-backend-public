package com.gatherit.api.ai.dto;

import java.util.List;

public record CommitResponse(List<Long> createdEventIds, int requested) {}

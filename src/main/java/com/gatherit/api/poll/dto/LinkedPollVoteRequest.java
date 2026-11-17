package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record LinkedPollVoteRequest(
        @NotBlank @Size(max = 20) String nickname,
        @NotNull Set<Long> availableSlotIds
) {}

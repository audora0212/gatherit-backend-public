package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotNull;

public record ConfirmRequest(
        @NotNull Long slotId
) {}

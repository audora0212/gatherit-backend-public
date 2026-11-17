package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.GroupColor;
import jakarta.validation.constraints.NotNull;

public record MyColorRequest(
        @NotNull GroupColor colorKey
) {}

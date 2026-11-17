package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.GroupColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GroupCreateRequest(
        @NotBlank @Size(min = 1, max = 50) String name,
        @NotNull GroupColor colorKey
) {}

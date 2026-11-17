package com.gatherit.api.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMeRequest(
        @Size(min = 1, max = 50) String nickname,
        @Size(max = 500) String profileImageUrl,
        /** 생성자 바 색 — #RRGGBB. null = 변경 안 함. */
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "색상은 #RRGGBB 형식이어야 합니다") String personalColor
) {}

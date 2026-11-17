package com.gatherit.api.poll.dto;

import com.gatherit.api.poll.entity.ConfirmPolicy;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 투표 수정 — 슬롯은 못 바꿈 (이미 투표한 사람들이 있을 수 있어서).
 * title / deadlineAt / confirmPolicy 만 변경 가능. 모두 nullable, null 이면 변경 안 함.
 */
public record PollUpdateRequest(
        @Size(max = 100) String title,
        Instant deadlineAt,
        ConfirmPolicy confirmPolicy
) {}

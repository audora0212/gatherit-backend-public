package com.gatherit.api.poll.dto;

import com.gatherit.api.poll.entity.ConfirmPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record PollCreateRequest(
        @NotNull Long groupId,
        @NotBlank @Size(max = 100) String title,
        @NotNull Instant deadlineAt,
        @NotNull ConfirmPolicy confirmPolicy,
        /** 후보 슬롯 — 10분 단위. 클라이언트가 미리 자르거나, 서버가 [start, end) 범위만 받고 분할할 수도 있음. 일단 클라가 자른다고 가정. */
        @NotEmpty List<SlotInput> slots
) {
    public record SlotInput(@NotNull Instant startAt, @NotNull Instant endAt) {}
}

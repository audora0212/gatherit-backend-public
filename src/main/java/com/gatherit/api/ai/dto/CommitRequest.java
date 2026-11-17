package com.gatherit.api.ai.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 사용자가 체크박스로 고른 후보들을 실제 등록. 기존 EventService.create 재사용.
 */
public record CommitRequest(
        /** 등록 대상 그룹 (최소 1개). */
        @NotEmpty List<Long> groupIds,
        @NotEmpty @Size(max = 30) List<CommitEvent> events,
        /** 이전 앱과의 요청 형식 호환용 선택 필드. 일정 등록과 사용량에는 반영하지 않는다. */
        Long importLogId,
        /** commit 시도당 1개 — 타임아웃 재탭의 중복 등록 방지 (선택, P1-1). */
        @Size(max = 64) String idempotencyKey
) {
    public record CommitEvent(
            @Size(max = 100) String title,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            Boolean allDay,
            @Size(max = 200) String location,
            @Size(max = 1000) String memo
    ) {}
}

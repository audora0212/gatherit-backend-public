package com.gatherit.api.ai.dto;

import java.util.List;

/**
 * 추출 응답 — 후보 + 중복 표시 + 사용량/쿼터.
 * importLogId는 요청이 일일 한도에 반영된 기록의 식별자이다.
 */
public record ExtractResponse(
        Long importLogId,
        List<EventCandidate> candidates,
        List<Duplicate> duplicates,
        /** 웹검색 출처(URL) — 사용자가 직접 진위 확인용. */
        List<Source> sources,
        String model,
        Usage usage,
        Quota quota,
        /** 후보가 상한(MAX_CANDIDATES)을 초과해 일부만 반환됐는지. */
        boolean truncated
) {
    /** 후보가 기존 일정과 겹칠 가능성 — 미리보기에서 경고 + 기본 체크 해제. */
    public record Duplicate(String tempId, Long existingEventId, String existingTitle) {}

    public record Source(String title, String url) {}

    public record Usage(int inputTokens, int outputTokens) {}

    public record Quota(int usedToday, int dailyLimit) {}
}

package com.gatherit.api.ai.extractor;

import java.util.List;

/**
 * LLM 추출 원시 결과 (provider 무관). 날짜는 문자열 — 서버가 파싱/정규화/검증(§5).
 * sources: 웹검색 그라운딩 출처(사용자가 직접 진위 확인용 URL).
 */
public record Extraction(
        List<RawCandidate> candidates,
        List<Source> sources,
        int inputTokens,
        int outputTokens,
        String model
) {
    public record RawCandidate(
            String title,
            String startAt,
            String endAt,
            boolean allDay,
            String location,
            double confidence,
            boolean needsReview,
            String sourceQuote
    ) {}

    /** 웹검색 그라운딩 출처. */
    public record Source(String title, String url) {}
}

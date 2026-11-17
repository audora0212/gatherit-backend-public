package com.gatherit.api.ai.dto;

import java.time.Instant;

/**
 * 서버 검증/정규화를 거친 일정 후보 (클라 미리보기/체크박스용).
 * AI 원시 출력(extractor.RawCandidate)을 AiScheduleService 가 파싱/정규화해 만든다.
 */
public record EventCandidate(
        /** 클라 선택/매핑용 임시 ID (c1, c2 ...). */
        String tempId,
        String title,
        Instant startAt,
        Instant endAt,
        boolean allDay,
        String location,
        String memo,
        /** 0~1. 낮으면(needsReview) 미리보기에서 기본 체크 해제. */
        double confidence,
        boolean needsReview,
        /** needsReview/낮은신뢰 사유 (예: "연도 추정", "날짜 모호"). */
        String reviewReason,
        /** 근거가 된 원문 구절. */
        String sourceQuote
) {}

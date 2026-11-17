package com.gatherit.api.ai.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 일정 추출 요청. text/prompt 중 최소 1개 (서비스에서 검증).
 * Phase 1 은 text(붙여넣기)/prompt 만 사용 — url/웹검색은 Phase 2/3.
 */
public record ExtractRequest(
        /** 자연어 지시 (선택). 예: "이 내용 일정으로 넣어줘". */
        @Size(max = 2000) String prompt,
        /** 붙여넣기 본문 (Phase 1 핵심 소스). */
        @Size(max = 8000) String text,
        /** 공지 URL (Phase 2 — 현재 미지원, 무시). */
        @Size(max = 2000) String url,
        /** 웹검색(그라운딩) 수행 여부. null/true 면 검색, false 면 입력 텍스트만 구조화. */
        Boolean search,
        /** 미리 선택한 등록 대상 그룹 (선택 — commit 단계에서 확정). */
        List<Long> groupIds
) {}

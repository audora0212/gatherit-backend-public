package com.gatherit.api.ai.extractor;

import java.time.LocalDate;

/**
 * 일정 추출 추상화 (provider-pluggable). 추출 단계만 분리 — 파이프라인(검증/commit/쿼터)은 공통.
 * 구현: GeminiExtractor (기본). 추후 OpenAi/Claude fallback 추가 가능.
 */
public interface LlmExtractor {

    /**
     * 소스 텍스트에서 일정 후보를 구조화 추출. 상대날짜 해석 기준일(todayKst) 전달.
     * webSearch=true 면 그라운딩(웹검색)으로 일정/출처를 찾고, false 면 입력 텍스트만 구조화(검색/출처 없음).
     */
    Extraction extract(String sourceText, LocalDate todayKst, boolean webSearch);

    /** 설정 여부 (API 키 등). false 면 컨트롤러가 503 안내. */
    boolean isConfigured();

    /** 사용 모델 식별자 (로그/응답 표기). */
    String model();
}

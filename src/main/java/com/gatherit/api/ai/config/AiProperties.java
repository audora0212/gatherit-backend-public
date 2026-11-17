package com.gatherit.api.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 일정 가져오기 설정 — application.yml 의 ai.* 바인딩.
 *
 * - extractor.provider: 추출 provider (현재 gemini 단일. 추후 openai/claude fallback).
 * - gemini.api-key: AI Studio 키 (GEMINI_API_KEY 환경 변수). 비면 기능 비활성.
 * - gemini.model: 기본 gemini-2.5-flash-lite (최저가 + 무료티어 + 한국어 그라운딩).
 * - quota.daily-limit: 유저당 하루 추출 호출 한도(적자 방지).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Extractor extractor = new Extractor();
    private Gemini gemini = new Gemini();
    private Quota quota = new Quota();

    @Getter
    @Setter
    public static class Extractor {
        private String provider = "gemini";
    }

    @Getter
    @Setter
    public static class Gemini {
        private String apiKey = "";
        private String model = "gemini-2.5-flash-lite";
        /** REST base — generativelanguage(개발 API). */
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    }

    @Getter
    @Setter
    public static class Quota {
        private int dailyLimit = 10;
    }
}

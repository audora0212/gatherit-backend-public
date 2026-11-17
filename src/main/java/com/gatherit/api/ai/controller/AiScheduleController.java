package com.gatherit.api.ai.controller;

import com.gatherit.api.ai.dto.CommitRequest;
import com.gatherit.api.ai.dto.CommitResponse;
import com.gatherit.api.ai.dto.ExtractRequest;
import com.gatherit.api.ai.dto.ExtractResponse;
import com.gatherit.api.ai.dto.QuotaResponse;
import com.gatherit.api.ai.service.AiScheduleService;
import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 일정 가져오기 — 텍스트/붙여넣기 → 후보 추출 → 사용자 체크박스 선택 → 등록.
 * 인증 USER (SecurityConfig anyRequest authenticated). 설계: docs/ai-schedule-import.md.
 */
@RestController
@RequestMapping("/api/ai/schedule")
@RequiredArgsConstructor
@Tag(name = "AI Schedule", description = "AI 일정 가져오기 (텍스트 추출 → 확인 후 등록)")
public class AiScheduleController {

    private final AiScheduleService service;

    @Operation(summary = "일정 후보 추출 (저장 X) — 미리보기/체크박스용")
    @PostMapping("/extract")
    public ApiResponse<ExtractResponse> extract(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody ExtractRequest req) {
        return ApiResponse.ok(service.extract(me.getUserId(), req));
    }

    @Operation(summary = "선택한 후보만 등록 (EventService 재사용)")
    @PostMapping("/commit")
    public ApiResponse<CommitResponse> commit(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody CommitRequest req) {
        return ApiResponse.ok(service.commit(me.getUserId(), req));
    }

    @Operation(summary = "오늘 사용량/한도")
    @GetMapping("/quota")
    public ApiResponse<QuotaResponse> quota(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(service.quota(me.getUserId()));
    }
}

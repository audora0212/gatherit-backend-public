package com.gatherit.api.event.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.dto.EventUpdateRequest;
import com.gatherit.api.event.service.EventService;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Event", description = "일정 (다중 그룹 공유)")
public class EventController {

    private final EventService eventService;

    @Operation(summary = "일정 생성 (groupIds 다중 가능)")
    @PostMapping
    public ApiResponse<EventResponse> create(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody EventCreateRequest req) {
        return ApiResponse.ok(eventService.create(me.getUserId(), req));
    }

    @Operation(summary = "캘린더 조회 — [from, to) 범위. 본인 그룹 일정만 반환")
    @GetMapping
    public ApiResponse<List<EventResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl me,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean includeRecurringSources) {
        return ApiResponse.ok(eventService.findInRange(me.getUserId(), from, to, includeRecurringSources));
    }

    @Operation(summary = "일정 검색 — 제목/장소/메모 키워드 (가시성 적용)")
    @GetMapping("/search")
    public ApiResponse<List<EventResponse>> search(
            @AuthenticationPrincipal UserDetailsImpl me,
            @RequestParam String q) {
        return ApiResponse.ok(eventService.search(me.getUserId(), q));
    }

    @Operation(summary = "일정 상세")
    @GetMapping("/{eventId}")
    public ApiResponse<EventResponse> detail(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId) {
        return ApiResponse.ok(eventService.getDetail(me.getUserId(), eventId));
    }

    @Operation(summary = "일정 수정 (생성자만)")
    @PatchMapping("/{eventId}")
    public ApiResponse<EventResponse> update(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId,
            @Valid @RequestBody EventUpdateRequest req) {
        return ApiResponse.ok(eventService.update(me.getUserId(), eventId, req));
    }

    @Operation(summary = "일정 삭제 (생성자만) — scope 로 반복 회차별 삭제. 기본 ALL(전체)")
    @DeleteMapping("/{eventId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId,
            @RequestParam(required = false) com.gatherit.api.event.dto.EditScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant occurrenceStart) {
        eventService.delete(me.getUserId(), eventId, scope, occurrenceStart);
        return ApiResponse.ok();
    }

    @Operation(summary = "내 캘린더(개인 그룹) 초기화 — 개인 그룹 일정만 삭제. removed 개수 반환")
    // ─── 디데이 핀 (v1.4 B) ───────────────────────────────────────────────

    @GetMapping("/dday-pins")
    public ApiResponse<java.util.List<com.gatherit.api.event.service.EventService.DdayPinResponse>> ddayPins(
            @AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(eventService.listDdayPins(me.getUserId()));
    }

    @PutMapping("/{eventId}/dday-pin")
    public ApiResponse<Void> pinDday(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId) {
        eventService.pinDday(me.getUserId(), eventId);
        return ApiResponse.ok();
    }

    @PutMapping("/{eventId}/dday-pin/primary")
    public ApiResponse<Void> setPrimaryDday(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId) {
        eventService.setPrimaryDday(me.getUserId(), eventId, true);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{eventId}/dday-pin/primary")
    public ApiResponse<Void> unsetPrimaryDday(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId) {
        eventService.setPrimaryDday(me.getUserId(), eventId, false);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{eventId}/dday-pin")
    public ApiResponse<Void> unpinDday(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long eventId) {
        eventService.unpinDday(me.getUserId(), eventId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/personal-calendar")
    public ApiResponse<Integer> resetPersonalCalendar(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(eventService.resetPersonalCalendar(me.getUserId()));
    }
}

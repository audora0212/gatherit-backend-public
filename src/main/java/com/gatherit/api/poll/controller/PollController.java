package com.gatherit.api.poll.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.poll.dto.ConfirmRequest;
import com.gatherit.api.poll.dto.PollCreateRequest;
import com.gatherit.api.poll.dto.PollResponse;
import com.gatherit.api.poll.dto.PollUpdateRequest;
import com.gatherit.api.poll.dto.PollShareResponse;
import com.gatherit.api.poll.dto.VoteRequest;
import com.gatherit.api.poll.service.PollService;
import com.gatherit.api.poll.service.PublicPollService;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/polls")
@RequiredArgsConstructor
@Tag(name = "Poll", description = "시간 투표 (낙관적 락 동시성)")
public class PollController {

    private final PollService pollService;
    private final PublicPollService publicPollService;

    @Operation(summary = "투표 생성")
    @PostMapping
    public ApiResponse<PollResponse> create(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody PollCreateRequest req) {
        return ApiResponse.ok(pollService.create(me.getUserId(), req));
    }

    @Operation(summary = "투표 상세 + 슬롯별 응답 집계")
    @GetMapping("/{pollId}")
    public ApiResponse<PollResponse> detail(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId) {
        return ApiResponse.ok(pollService.getDetail(me.getUserId(), pollId));
    }

    @Operation(summary = "내가 미응답인 진행 중 투표 수 — 라이브 카드 알림 줄용")
    @GetMapping("/pending-count")
    public ApiResponse<Long> pendingCount(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(pollService.countPending(me.getUserId()));
    }

    @Operation(summary = "그룹의 투표 목록")
    @GetMapping
    public ApiResponse<List<PollResponse>> listByGroup(
            @AuthenticationPrincipal UserDetailsImpl me,
            @RequestParam Long groupId) {
        return ApiResponse.ok(pollService.findByGroup(me.getUserId(), groupId));
    }

    @Operation(summary = "내 가용 슬롯 응답")
    @PostMapping("/{pollId}/vote")
    public ApiResponse<Void> vote(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId,
            @Valid @RequestBody VoteRequest req) {
        pollService.vote(me.getUserId(), pollId, req);
        return ApiResponse.ok();
    }

    @Operation(summary = "비회원 웹 투표 공유 링크 발급 (발의자만)")
    @PostMapping("/{pollId}/share")
    public ApiResponse<PollShareResponse> share(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId) {
        return ApiResponse.ok(publicPollService.issueShare(me.getUserId(), pollId));
    }

    @Operation(summary = "내 투표 전체 취소 — 응답한 적 없는 상태로 되돌림")
    @DeleteMapping("/{pollId}/vote")
    public ApiResponse<Void> cancelMyVote(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId) {
        pollService.cancelMyVote(me.getUserId(), pollId);
        return ApiResponse.ok();
    }

    @Operation(summary = "확정 — 동시 클릭 시 한 명만 성공 (낙관적 락)")
    @PostMapping("/{pollId}/confirm")
    public ApiResponse<PollResponse> confirm(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId,
            @Valid @RequestBody ConfirmRequest req) {
        return ApiResponse.ok(pollService.confirm(me.getUserId(), pollId, req));
    }

    @Operation(summary = "투표 수정 (발의자만, 미확정 상태에서만)")
    @PatchMapping("/{pollId}")
    public ApiResponse<PollResponse> update(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId,
            @Valid @RequestBody PollUpdateRequest req) {
        return ApiResponse.ok(pollService.update(me.getUserId(), pollId, req));
    }

    @Operation(summary = "투표 삭제 (발의자만)")
    @DeleteMapping("/{pollId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long pollId) {
        pollService.delete(me.getUserId(), pollId);
        return ApiResponse.ok();
    }
}

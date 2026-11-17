package com.gatherit.api.poll.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.group.dto.GroupResponse;
import com.gatherit.api.poll.dto.LinkedPollClaimRequest;
import com.gatherit.api.poll.dto.LinkedPollCodeClaimRequest;
import com.gatherit.api.poll.dto.LinkedPollVoteRequest;
import com.gatherit.api.poll.dto.PublicPollResponse;
import com.gatherit.api.poll.service.PublicPollService;
import com.gatherit.api.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/polls/linked")
@RequiredArgsConstructor
public class LinkedPollController {

    private final PublicPollService publicPollService;

    @GetMapping
    public ApiResponse<List<PublicPollResponse>> list(
            @AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(publicPollService.findLinkedPolls(me.getUserId()));
    }

    @GetMapping("/{shareToken}")
    public ApiResponse<PublicPollResponse> detail(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable String shareToken) {
        return ApiResponse.ok(publicPollService.getLinkedPoll(me.getUserId(), shareToken));
    }

    @PostMapping("/claim-code")
    public ApiResponse<PublicPollResponse> claimByCode(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody LinkedPollCodeClaimRequest request) {
        return ApiResponse.ok(publicPollService.claimLinkedPollByCode(me.getUserId(), request));
    }

    @PostMapping("/{shareToken}/claim")
    public ApiResponse<PublicPollResponse> claim(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable String shareToken,
            @Valid @RequestBody LinkedPollClaimRequest request) {
        return ApiResponse.ok(publicPollService.claimLinkedPoll(me.getUserId(), shareToken, request));
    }

    @PostMapping("/{shareToken}/vote")
    public ApiResponse<PublicPollResponse> vote(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable String shareToken,
            @Valid @RequestBody LinkedPollVoteRequest request) {
        return ApiResponse.ok(publicPollService.voteLinkedPoll(me.getUserId(), shareToken, request));
    }

    @PostMapping("/{shareToken}/join-group")
    public ApiResponse<GroupResponse> joinGroup(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable String shareToken) {
        return ApiResponse.ok(publicPollService.joinLinkedPollGroup(me.getUserId(), shareToken));
    }
}

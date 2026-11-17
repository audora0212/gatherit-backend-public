package com.gatherit.api.poll.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.poll.dto.PublicPollHandoffRequest;
import com.gatherit.api.poll.dto.PublicPollHandoffResponse;
import com.gatherit.api.poll.dto.PublicPollResponse;
import com.gatherit.api.poll.dto.PublicPollVoteRequest;
import com.gatherit.api.poll.dto.PublicPollVoteResponse;
import com.gatherit.api.poll.service.PublicPollService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/polls")
@RequiredArgsConstructor
public class PublicPollController {

    private final PublicPollService publicPollService;

    @GetMapping("/{shareToken}")
    public ApiResponse<PublicPollResponse> detail(
            @PathVariable String shareToken,
            @RequestHeader(name = "X-Poll-Participant-Token", required = false) String participantToken) {
        return ApiResponse.ok(publicPollService.getPublicPoll(shareToken, participantToken));
    }

    @PostMapping("/{shareToken}/vote")
    public ApiResponse<PublicPollVoteResponse> vote(
            @PathVariable String shareToken,
            @Valid @RequestBody PublicPollVoteRequest request) {
        return ApiResponse.ok(publicPollService.vote(shareToken, request));
    }

    @PostMapping("/{shareToken}/handoff")
    public ApiResponse<PublicPollHandoffResponse> handoff(
            @PathVariable String shareToken,
            @Valid @RequestBody PublicPollHandoffRequest request) {
        return ApiResponse.ok(publicPollService.issueHandoff(shareToken, request));
    }
}

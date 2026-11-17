package com.gatherit.api.group.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.group.dto.GroupCreateRequest;
import com.gatherit.api.group.dto.GroupMemberResponse;
import com.gatherit.api.group.dto.GroupPreviewResponse;
import com.gatherit.api.group.dto.GroupResponse;
import com.gatherit.api.group.dto.GroupUpdateRequest;
import com.gatherit.api.group.dto.InviteResponse;
import com.gatherit.api.group.dto.JoinRequest;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Tag(name = "Group", description = "그룹 + 멤버십")
public class GroupController {

    private final GroupService groupService;

    @Operation(summary = "그룹 생성")
    @PostMapping
    public ApiResponse<GroupResponse> create(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody GroupCreateRequest req) {
        return ApiResponse.ok(groupService.create(me.getUserId(), req));
    }

    @Operation(summary = "내가 속한 그룹 목록")
    @GetMapping
    public ApiResponse<List<GroupResponse>> myGroups(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(groupService.findMyGroups(me.getUserId()));
    }

    @Operation(summary = "그룹 상세")
    @GetMapping("/{groupId}")
    public ApiResponse<GroupResponse> detail(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        return ApiResponse.ok(groupService.getDetail(me.getUserId(), groupId));
    }

    @Operation(summary = "그룹 멤버 목록")
    @GetMapping("/{groupId}/members")
    public ApiResponse<List<GroupMemberResponse>> members(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        return ApiResponse.ok(groupService.listMembers(me.getUserId(), groupId));
    }

    @Operation(summary = "그룹 정보 수정 (소유자만)")
    @PatchMapping("/{groupId}")
    public ApiResponse<GroupResponse> update(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupUpdateRequest req) {
        return ApiResponse.ok(groupService.update(me.getUserId(), groupId, req));
    }

    @Operation(summary = "내 그룹 색만 변경 (개인 설정 — 다른 멤버에게 영향 X). 개인 캘린더에도 적용.")
    @PatchMapping("/{groupId}/my-color")
    public ApiResponse<GroupResponse> updateMyColor(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @Valid @RequestBody com.gatherit.api.group.dto.MyColorRequest req) {
        return ApiResponse.ok(groupService.updateMyColor(me.getUserId(), groupId, req.colorKey()));
    }

    @Operation(summary = "그룹 삭제 (소유자만)")
    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        groupService.delete(me.getUserId(), groupId);
        return ApiResponse.ok();
    }

    @Operation(summary = "그룹 떠나기")
    @DeleteMapping("/{groupId}/leave")
    public ApiResponse<Void> leave(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        groupService.leave(me.getUserId(), groupId);
        return ApiResponse.ok();
    }

    @Operation(summary = "멤버 강퇴 (소유자만)")
    @DeleteMapping("/{groupId}/members/{userId}")
    public ApiResponse<Void> kick(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @PathVariable Long userId) {
        groupService.kick(me.getUserId(), groupId, userId);
        return ApiResponse.ok();
    }

    @Operation(summary = "그룹장 양도 (현 소유자만 — 대상 멤버에게 소유권 이전)")
    @PostMapping("/{groupId}/members/{userId}/transfer-owner")
    public ApiResponse<Void> transferOwner(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @PathVariable Long userId) {
        groupService.transferOwner(me.getUserId(), groupId, userId);
        return ApiResponse.ok();
    }

    @Operation(summary = "초대 토큰 발급 (멤버 누구나)")
    @PostMapping("/{groupId}/invite")
    public ApiResponse<InviteResponse> invite(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        return ApiResponse.ok(groupService.issueInvite(me.getUserId(), groupId));
    }

    @Operation(summary = "초대 토큰 미리보기 — 가입 전 그룹 정보 확인 (참여 확인 모달용)")
    @GetMapping("/preview")
    public ApiResponse<GroupPreviewResponse> previewByToken(
            @AuthenticationPrincipal UserDetailsImpl me,
            @RequestParam("token") String token) {
        return ApiResponse.ok(groupService.previewByToken(me.getUserId(), token));
    }

    @Operation(summary = "초대 토큰으로 그룹 가입")
    @PostMapping("/join")
    public ApiResponse<GroupResponse> join(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody JoinRequest req) {
        return ApiResponse.ok(groupService.joinByToken(me.getUserId(), req.token()));
    }
}

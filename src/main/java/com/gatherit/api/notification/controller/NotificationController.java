package com.gatherit.api.notification.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.notification.dto.GroupNotifPrefResponse;
import com.gatherit.api.notification.dto.GroupNotifPrefUpdateRequest;
import com.gatherit.api.notification.dto.NotificationResponse;
import com.gatherit.api.notification.dto.UserNotifPrefsResponse;
import com.gatherit.api.notification.dto.UserNotifPrefsUpdateRequest;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notification", description = "인앱 알림")
public class NotificationController {

    private final NotificationService service;

    @Operation(summary = "내 알림 목록")
    @GetMapping
    public ApiResponse<List<NotificationResponse>> list(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(service.list(me.getUserId()));
    }

    @Operation(summary = "안 읽은 알림 개수")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unread(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(Map.of("count", service.unreadCount(me.getUserId())));
    }

    @Operation(summary = "단건 읽음 처리")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal UserDetailsImpl me, @PathVariable Long id) {
        service.markRead(me.getUserId(), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "모두 읽음")
    @PostMapping("/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal UserDetailsImpl me) {
        service.markAllRead(me.getUserId());
        return ApiResponse.ok();
    }

    @Operation(summary = "단건 삭제(dismiss) — 죽은 참조 알림 등 직접 제거. 본인 소유만, 없으면 무시")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserDetailsImpl me, @PathVariable Long id) {
        service.delete(me.getUserId(), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "내 알림 prefs 조회 — 전역 + 그룹별")
    @GetMapping("/prefs")
    public ApiResponse<UserNotifPrefsResponse> getPrefs(@AuthenticationPrincipal UserDetailsImpl me) {
        return ApiResponse.ok(service.getPrefs(me.getUserId()));
    }

    @Operation(summary = "내 전역 prefs 부분 수정 (allOff / leadTime)")
    @PatchMapping("/prefs")
    public ApiResponse<UserNotifPrefsResponse> updatePrefs(
            @AuthenticationPrincipal UserDetailsImpl me,
            @Valid @RequestBody UserNotifPrefsUpdateRequest req) {
        return ApiResponse.ok(service.updatePrefs(me.getUserId(), req));
    }

    @Operation(summary = "그룹 단위 prefs 부분 수정 (enabled + 8 type)")
    @PatchMapping("/prefs/groups/{groupId}")
    public ApiResponse<GroupNotifPrefResponse> updateGroupPref(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupNotifPrefUpdateRequest req) {
        return ApiResponse.ok(service.updateGroupPref(me.getUserId(), groupId, req));
    }
}

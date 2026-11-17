package com.gatherit.api.user.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.security.UserDetailsImpl;
import com.gatherit.api.user.dto.MeStatsResponse;
import com.gatherit.api.user.dto.PushTokenRequest;
import com.gatherit.api.user.dto.UpdateMeRequest;
import com.gatherit.api.user.dto.UserMeResponse;
import com.gatherit.api.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "User", description = "현재 사용자 정보")
public class UserController {

    private final UserService userService;

    @Operation(summary = "내 정보 조회")
    @GetMapping
    public ApiResponse<UserMeResponse> me(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ApiResponse.ok(userService.getMe(principal.getUserId()));
    }

    @Operation(summary = "MY 탭 stats — 그룹/사진/일정 카운트 한 번에")
    @GetMapping("/stats")
    public ApiResponse<MeStatsResponse> stats(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ApiResponse.ok(userService.getStats(principal.getUserId()));
    }

    @Operation(summary = "내 정보 수정 (닉네임 / 프로필 이미지)")
    @PatchMapping
    public ApiResponse<UserMeResponse> updateMe(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @Valid @RequestBody UpdateMeRequest req) {
        return ApiResponse.ok(userService.updateMe(principal.getUserId(), req));
    }

    @Operation(summary = "회원탈퇴 — 본인이 OWNER 인 그룹 cascade 삭제 + 세션 종료")
    @DeleteMapping
    public ApiResponse<Void> deleteMe(@AuthenticationPrincipal UserDetailsImpl principal) {
        userService.deleteMyAccount(principal.getUserId());
        return ApiResponse.ok();
    }

    @Operation(summary = "Push 토큰 등록/갱신 — null/빈값 이면 제거 (권한 회수 시)")
    @PostMapping("/push-token")
    public ApiResponse<Void> updatePushToken(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody PushTokenRequest req,
            HttpServletRequest httpReq) {
        String t = req.expoPushToken();
        // 디버깅용 — Expo→APNs 전환 시점에 어떤 token 이 어떻게 들어오는지 추적.
        // 안정화 후 제거 또는 DEBUG 레벨로 강등.
        org.slf4j.LoggerFactory.getLogger(UserController.class).info(
                "[push-token] user={} kind={} preview={}",
                principal.getUserId(),
                t == null || t.isBlank() ? "null"
                        : t.startsWith("debug:") ? "DEBUG"
                        : t.startsWith("ExponentPushToken") ? "EXPO"
                        : t.length() == 64 ? "APNS_HEX64" : "OTHER(len=" + t.length() + ")",
                t == null || t.isBlank() ? "" : t.startsWith("debug:") ? t : t.length() > 16 ? t.substring(0, 8) + "..." : t);
        // debug: prefix 는 디버깅 ping — DB 업데이트 안 함.
        if (t != null && t.startsWith("debug:")) {
            return ApiResponse.ok();
        }
        userService.updateExpoPushToken(principal.getUserId(), t, resolvePushPlatform(httpReq, t));
        return ApiResponse.ok();
    }

    /**
     * 푸시 토큰 플랫폼 추출 — 전송 시 iOS=APNs / android=FCM 분기용.
     * 1순위: X-App-Version 헤더의 마지막 토큰 ("1.2.0 (57) ios" → "ios").
     * 2순위: 토큰 형식 추론 — APNs 는 64자리 hex, FCM 은 더 긴 토큰.
     */
    private static String resolvePushPlatform(HttpServletRequest req, String token) {
        try {
            String v = req != null ? req.getHeader("X-App-Version") : null;
            if (v != null && !v.isBlank()) {
                String trimmed = v.trim();
                String last = trimmed.substring(trimmed.lastIndexOf(' ') + 1).toLowerCase();
                if (last.equals("ios") || last.equals("android")) return last;
            }
        } catch (Exception ignored) { /* fall through to token heuristic */ }
        if (token != null && token.matches("^[0-9a-fA-F]{64}$")) return "ios";   // APNs hex64
        if (token != null && token.length() > 64) return "android";             // FCM (긴 토큰)
        return null;
    }
}

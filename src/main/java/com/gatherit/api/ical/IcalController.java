package com.gatherit.api.ical;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 캘린더 .ics 내보내기 / 구독.
 *  - 토큰 조회/재발급: /api/me/ical-token (JWT)
 *  - 그룹 .ics 다운로드: /api/groups/{groupId}/calendar.ics (JWT, 멤버만)
 *  - 그룹 구독 피드:     /api/ical/{token}/{groupId}.ics (public, 토큰 식별 — SecurityConfig 허용)
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "iCal", description = "캘린더 .ics 내보내기 / 구독")
public class IcalController {

    private final IcalService icalService;
    private final IcalImportService icalImportService;

    private static final MediaType ICS = MediaType.parseMediaType("text/calendar;charset=UTF-8");

    @Operation(summary = "외부 .ics import — 내 캘린더(개인)로 일회성 가져오기")
    @PostMapping("/api/me/calendar/import")
    public ApiResponse<IcalImportService.ImportResult> importIcs(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody IcalImportRequest req) {
        return ApiResponse.ok(icalImportService.importIcs(principal.getUserId(), req.ics()));
    }

    @Operation(summary = "내 iCal 구독 토큰 조회 (없으면 생성)")
    @GetMapping("/api/me/ical-token")
    public ApiResponse<IcalTokenResponse> getToken(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ApiResponse.ok(new IcalTokenResponse(icalService.ensureToken(principal.getUserId())));
    }

    @Operation(summary = "내 iCal 구독 토큰 재발급 — 기존 구독 URL 무효화")
    @PostMapping("/api/me/ical-token/rotate")
    public ApiResponse<IcalTokenResponse> rotateToken(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ApiResponse.ok(new IcalTokenResponse(icalService.rotateToken(principal.getUserId())));
    }

    @Operation(summary = "내 일정 .ics 다운로드 (groups 미지정 시 전체)")
    @GetMapping("/api/me/calendar.ics")
    public ResponseEntity<String> downloadAll(@AuthenticationPrincipal UserDetailsImpl principal,
                                              @RequestParam(name = "groups", required = false) List<Long> groups) {
        return ResponseEntity.ok()
                .contentType(ICS)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gatherit.ics\"")
                .body(icalService.buildUserCalendar(principal.getUserId(), groups));
    }

    @Operation(summary = "내 일정 구독 피드 (webcal, 토큰; groups 미지정 시 전체)")
    @GetMapping("/api/ical/{token}.ics")
    public ResponseEntity<String> subscribeAll(@PathVariable String token,
                                               @RequestParam(name = "groups", required = false) List<Long> groups) {
        return icalService.buildUserCalendarByToken(token, groups)
                .map(ics -> ResponseEntity.ok().contentType(ICS).body(ics))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "그룹 일정 .ics 다운로드 (멤버만)")
    @GetMapping("/api/groups/{groupId}/calendar.ics")
    public ResponseEntity<String> download(@AuthenticationPrincipal UserDetailsImpl principal,
                                           @PathVariable Long groupId) {
        return icalService.buildGroupCalendar(groupId, principal.getUserId())
                .map(ics -> ResponseEntity.ok()
                        .contentType(ICS)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"gatherit-group-" + groupId + ".ics\"")
                        .body(ics))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "그룹 일정 구독 피드 (webcal, 토큰)")
    @GetMapping("/api/ical/{token}/{groupId}.ics")
    public ResponseEntity<String> subscribe(@PathVariable String token, @PathVariable Long groupId) {
        return icalService.buildGroupCalendarByToken(token, groupId)
                .map(ics -> ResponseEntity.ok().contentType(ICS).body(ics))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

package com.gatherit.api.announcement;

import com.gatherit.api.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 앱 공지 사용자 조회.
 *  - GET /active : 진입 모달용 (현재 노출 기간)
 *  - GET        : MY 공지 리스트(archive)
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcement", description = "앱 공지")
public class AnnouncementController {

    private final AnnouncementService service;

    @Operation(summary = "현재 노출 공지 (진입 모달용)")
    @GetMapping("/active")
    public ApiResponse<List<AnnouncementResponse>> active() {
        return ApiResponse.ok(service.getActive());
    }

    @Operation(summary = "공지 리스트 (최근 50, MY 공지사항)")
    @GetMapping
    public ApiResponse<List<AnnouncementResponse>> list() {
        return ApiResponse.ok(service.list());
    }
}

package com.gatherit.api.popup;

import com.gatherit.api.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 인앱 공지 모달(팝업) — MY 공지사항(announcement)과 별개 기능 (v1.4).
 *  - GET /active : 앱 콜드스타트 모달 후보 (enabled+기간 필터, 버전/dismiss 는 앱 판정)
 */
@RestController
@RequestMapping("/api/popups")
@RequiredArgsConstructor
@Tag(name = "Popup", description = "인앱 공지 모달")
public class PopupController {

    private final PopupRepository repository;

    @Operation(summary = "현재 노출 팝업 (앱 콜드스타트)")
    @GetMapping("/active")
    @Transactional(readOnly = true)
    public ApiResponse<List<PopupDtos.Response>> active() {
        return ApiResponse.ok(repository.findActive(Instant.now()).stream()
                .map(PopupDtos.Response::from).toList());
    }

    @Operation(summary = "지난 공지 아카이브 (MY 공지사항 화면)")
    @GetMapping("/archive")
    @Transactional(readOnly = true)
    public ApiResponse<List<PopupDtos.Response>> archive() {
        return ApiResponse.ok(repository.findTop50ByEnabledTrueAndStartsAtLessThanEqualOrderByStartsAtDesc(Instant.now())
                .stream().map(PopupDtos.Response::from).toList());
    }
}

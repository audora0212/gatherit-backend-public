package com.gatherit.api.app;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.config.AppUpdateProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 메타 — 버전/업데이트 안내. 콜드스타트(미인증)에서도 호출하므로 public (SecurityConfig permitAll).
 */
@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
@Tag(name = "App", description = "앱 메타 — 버전/업데이트 안내 (public)")
public class AppVersionController {

    private final AppUpdateProperties props;

    @Operation(summary = "플랫폼별 최신/최소 지원 버전 + 스토어 URL (public, 콜드스타트 업데이트 안내용)")
    @GetMapping("/version")
    public ApiResponse<AppVersionResponse> version() {
        return ApiResponse.ok(new AppVersionResponse(
                toDto(props.getIos()),
                toDto(props.getAndroid())
        ));
    }

    private AppVersionResponse.Platform toDto(AppUpdateProperties.Platform p) {
        return new AppVersionResponse.Platform(p.getLatest(), p.getMin(), p.getStoreUrl());
    }
}

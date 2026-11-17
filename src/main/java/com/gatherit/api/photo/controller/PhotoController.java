package com.gatherit.api.photo.controller;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.photo.dto.PhotoResponse;
import com.gatherit.api.photo.service.PhotoService;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 사진 — 로컬 디스크 저장 + 인앱 서빙.
 *  - 업로드: 멀티파트 batch (한 번에 30장)
 *  - 조회: /api/photos/{id}/file (그룹 멤버만, auth header 필요)
 *  - ZIP 다운로드: /api/groups/{groupId}/photos/download?ids=1,2,3 (최대 100장)
 *  - 삭제: /api/photos/{id} (업로드한 본인만)
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Photo", description = "그룹 사진 (로컬 저장, 7일 TTL)")
public class PhotoController {

    private final PhotoService photoService;

    @Operation(summary = "사진 batch 업로드 (최대 30장)")
    @PostMapping(value = "/api/groups/{groupId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<PhotoResponse>> upload(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "batchId", required = false) String batchId) {
        return ApiResponse.ok(photoService.uploadBatch(me.getUserId(), groupId, files, batchId));
    }

    @Operation(summary = "그룹 사진 목록")
    @GetMapping("/api/groups/{groupId}/photos")
    public ApiResponse<List<PhotoResponse>> listByGroup(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId) {
        return ApiResponse.ok(photoService.listByGroup(me.getUserId(), groupId));
    }

    @Operation(summary = "사진 단일 파일 조회 (auth 필요)")
    @GetMapping("/api/photos/{photoId}/file")
    public ResponseEntity<?> getFile(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long photoId) {
        PhotoService.PhotoFile pf = photoService.loadFile(me.getUserId(), photoId);
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pf.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
        if (pf.contentLength() >= 0) builder.contentLength(pf.contentLength());
        return builder.body(pf.resource());
    }

    @Operation(summary = "여러 사진 ZIP 으로 다운로드 (최대 100장)")
    @GetMapping("/api/groups/{groupId}/photos/download")
    public ResponseEntity<?> downloadZip(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @RequestParam("ids") List<Long> ids) {
        PhotoService.PhotoFile pf = photoService.downloadZip(me.getUserId(), groupId, ids);
        String encodedName = URLEncoder.encode(pf.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pf.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + pf.filename() + "\"; filename*=UTF-8''" + encodedName)
                .body(pf.resource());
    }

    @Operation(summary = "사진 삭제 — 그룹 photoDeletePolicy 에 따라")
    @DeleteMapping("/api/photos/{photoId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long photoId) {
        photoService.delete(me.getUserId(), photoId);
        return ApiResponse.ok();
    }

    @Operation(summary = "사진 일괄 삭제 — 권한 없는 건 skip, 실제 삭제 수 반환")
    @DeleteMapping("/api/groups/{groupId}/photos")
    public ApiResponse<Integer> deleteBatch(
            @AuthenticationPrincipal UserDetailsImpl me,
            @PathVariable Long groupId,
            @RequestParam("ids") List<Long> ids) {
        return ApiResponse.ok(photoService.deleteBatch(me.getUserId(), groupId, ids));
    }
}

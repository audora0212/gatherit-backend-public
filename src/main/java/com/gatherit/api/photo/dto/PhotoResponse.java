package com.gatherit.api.photo.dto;

import com.gatherit.api.photo.entity.Photo;

import java.time.Instant;

public record PhotoResponse(
        Long id,
        Long groupId,
        Long uploadedBy,
        /** 사진 조회용 URL (auth header 포함해서 fetch). 로컬: /api/photos/{id}/file */
        String url,
        String mimeType,
        String originalName,
        Long sizeBytes,
        Instant uploadedAt,
        Instant expiresAt
) {
    public static PhotoResponse of(Photo p, String url) {
        return new PhotoResponse(
                p.getId(), p.getGroupId(), p.getUploadedBy(), url,
                p.getMimeType(), p.getOriginalName(), p.getSizeBytes(),
                p.getUploadedAt(), p.getExpiresAt()
        );
    }
}

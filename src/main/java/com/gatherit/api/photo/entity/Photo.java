package com.gatherit.api.photo.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@Table(name = "photos",
    indexes = {
        @Index(name = "idx_photos_group", columnList = "group_id"),
        @Index(name = "idx_photos_expires_at", columnList = "expires_at"),
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Photo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    /**
     * 저장 키 — 로컬은 디스크 상대경로 (e.g. "groups/{groupId}/{uuid}.jpg"),
     * S3 전환 시 동일 포맷의 object key 로 재사용.
     * 컬럼명은 호환성 위해 s3_key 유지.
     */
    @Column(name = "s3_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 50)
    private String mimeType;

    /** 원본 파일명 — ZIP 다운로드 시 사용자 친화적 이름. */
    @Column(name = "original_name", length = 200)
    private String originalName;

    /** 파일 크기 (bytes). 통계/디버그용. */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    /** 만료 시각 = uploaded_at + N days (config). cron 이 지나면 row + 파일 삭제. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder
    private Photo(Long groupId, Long uploadedBy, String storageKey, String mimeType,
                  String originalName, Long sizeBytes,
                  Instant uploadedAt, Instant expiresAt) {
        this.groupId = groupId;
        this.uploadedBy = uploadedBy;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.originalName = originalName;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
        this.expiresAt = expiresAt;
    }
}

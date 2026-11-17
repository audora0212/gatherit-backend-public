package com.gatherit.api.photo.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * 사진 batch 업로드 멱등성 영수증 — [다시 시도]가 통째 중복 업로드되는 문제 방지 (엣지 감사 P1-2).
 * 클라가 업로드 시도당 1개의 batchId 를 보내고, 같은 (user, batch_id) 가 다시 오면
 * 업로드를 다시 받지 않고 저장된 사진 목록을 그대로 돌려준다.
 */
@Entity
@Getter
@Table(name = "photo_upload_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_photo_upload_user_batch", columnNames = {"user_id", "batch_id"}),
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhotoUploadReceipt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "batch_id", nullable = false, length = 64)
    private String batchId;

    /** 생성된 photo id CSV — 재요청 시 동일 응답 재구성용. */
    @Column(name = "photo_ids", nullable = false, length = 1000)
    private String photoIds;

    public PhotoUploadReceipt(Long userId, String batchId, List<Long> photoIds) {
        this.userId = userId;
        this.batchId = batchId;
        this.photoIds = photoIds.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    public List<Long> photoIdList() {
        if (photoIds.isBlank()) return List.of();
        return Arrays.stream(photoIds.split(",")).map(Long::valueOf).toList();
    }
}

package com.gatherit.api.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 성공한 일정 추출을 사용자별 일일 한도에 한 건 기록한다. */
@Entity
@Getter
@Table(name = "ai_import_logs", indexes = {
        @Index(name = "idx_ai_import_user_created", columnList = "user_id, created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AiImportLog(Long userId) {
        this.userId = userId;
    }

    public static AiImportLog ofExtract(Long userId) {
        return new AiImportLog(userId);
    }

    @PrePersist
    private void recordCreation() {
        createdAt = Instant.now();
    }
}

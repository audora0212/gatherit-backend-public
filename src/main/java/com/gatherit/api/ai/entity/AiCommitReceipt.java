package com.gatherit.api.ai.entity;

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
 * AI 일괄 등록(commit) 멱등성 영수증 — 타임아웃 후 재탭 시 전 건 2배 등록 방지 (엣지 감사 P1-1).
 * 클라가 commit 시도당 1개의 idempotencyKey 를 보내고, 같은 (user, key) 가 다시 오면
 * 새로 만들지 않고 저장된 결과를 그대로 돌려준다.
 */
@Entity
@Getter
@Table(name = "ai_commit_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ai_commit_user_key", columnNames = {"user_id", "idem_key"}),
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCommitReceipt extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String idemKey;

    /** 생성된 event id CSV — 재요청 시 동일 응답 재구성용. */
    @Column(name = "created_event_ids", nullable = false, length = 1000)
    private String createdEventIds;

    @Column(name = "requested", nullable = false)
    private int requested;

    public AiCommitReceipt(Long userId, String idemKey, List<Long> eventIds, int requested) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.createdEventIds = eventIds.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        this.requested = requested;
    }

    public List<Long> eventIdList() {
        if (createdEventIds.isBlank()) return List.of();
        return Arrays.stream(createdEventIds.split(",")).map(Long::valueOf).toList();
    }
}

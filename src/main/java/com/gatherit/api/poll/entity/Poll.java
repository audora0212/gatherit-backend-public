package com.gatherit.api.poll.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@Table(name = "polls",
    indexes = {
        @Index(name = "idx_polls_group", columnList = "group_id"),
        @Index(name = "idx_polls_deadline", columnList = "deadline_at"),
        @Index(name = "uk_polls_public_share_token", columnList = "public_share_token", unique = true),
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Poll extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirm_policy", nullable = false, length = 20)
    private ConfirmPolicy confirmPolicy;

    /** 확정된 슬롯 ID. null = 진행 중 */
    @Column(name = "confirmed_slot_id")
    private Long confirmedSlotId;

    /**
     * 확정 후 생성된 Event 의 ID. 직접 삭제돼도 ID를 유지해 재등록을 막는다.
     * 그룹 탈퇴로 자동 삭제된 경우에만 clearLinkedEvent() 로 비워 재등록을 허용한다.
     */
    @Column(name = "linked_event_id")
    private Long linkedEventId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 마감(deadline) 도달 알림을 발의자에게 이미 보냈는지 — 스케줄러 중복 발송 방지. */
    @Column(name = "deadline_notified", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean deadlineNotified = false;

    /** 비회원 웹 투표용 공개 링크. 128-bit UUID hex라 추측이 사실상 불가능하다. */
    @Column(name = "public_share_token", length = 32, unique = true)
    private String publicShareToken;

    /** 공개 링크는 투표 마감 후 7일까지 결과 확인 가능. */
    @Column(name = "public_share_expires_at")
    private Instant publicShareExpiresAt;

    /** 낙관적 락용 버전 — 동시 confirm race condition 방지 */
    @Version
    @Column(nullable = false)
    private Long version;

    @Builder
    private Poll(Long groupId, String title, Instant deadlineAt, ConfirmPolicy confirmPolicy, Long createdBy) {
        this.groupId = groupId;
        this.title = title;
        this.deadlineAt = deadlineAt;
        this.confirmPolicy = confirmPolicy;
        this.createdBy = createdBy;
    }

    public boolean isProposer(Long userId) {
        return this.createdBy.equals(userId);
    }

    public boolean isConfirmed() {
        return this.confirmedSlotId != null;
    }

    public boolean isDeadlinePassed(Instant now) {
        return now.isAfter(this.deadlineAt);
    }

    public void confirm(Long slotId) {
        this.confirmedSlotId = slotId;
    }

    public void markDeadlineNotified() {
        this.deadlineNotified = true;
    }

    public boolean hasActivePublicShare(Instant now) {
        return publicShareToken != null
                && publicShareExpiresAt != null
                && publicShareExpiresAt.isAfter(now);
    }

    public void enablePublicShare(String token, Instant expiresAt) {
        this.publicShareToken = token;
        this.publicShareExpiresAt = expiresAt;
    }

    /** 첫 "내 일정에 추가" 시 호출 — 이미 set 이면 false 리턴 (그 사람이 먼저 추가했다는 신호). */
    public boolean linkEventOnce(Long eventId) {
        if (this.linkedEventId != null) return false;
        this.linkedEventId = eventId;
        return true;
    }

    /**
     * 연결된 일정이 삭제됐을 때 호출 — linkedEventId 리셋.
     * 확정(confirmedSlotId)은 유지하므로, 다른 멤버가 "내 일정에 추가"로 재등록 가능.
     * (예: 확정 일정을 먼저 추가했던 사람이 탈퇴해 그 일정이 삭제된 경우)
     */
    public void clearLinkedEvent() {
        this.linkedEventId = null;
    }

    /** 발의자 수정 — null 인 필드는 건너뜀. */
    public void editBasic(String title, Instant deadlineAt, ConfirmPolicy confirmPolicy) {
        if (title != null) this.title = title;
        if (deadlineAt != null) this.deadlineAt = deadlineAt;
        if (confirmPolicy != null) this.confirmPolicy = confirmPolicy;
    }
}

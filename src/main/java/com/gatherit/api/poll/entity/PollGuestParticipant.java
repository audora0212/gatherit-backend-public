package com.gatherit.api.poll.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 공개 웹 링크에서 닉네임으로 참여한 비회원. */
@Entity
@Getter
@Table(name = "poll_guest_participants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_poll_guest_nickname", columnNames = {"poll_id", "nickname"}),
                @UniqueConstraint(name = "uk_poll_guest_edit_token", columnNames = {"poll_id", "edit_token_hash"}),
                @UniqueConstraint(name = "uk_poll_guest_linked_user", columnNames = {"poll_id", "linked_user_id"}),
                @UniqueConstraint(name = "uk_poll_guest_handoff_code", columnNames = {"handoff_code_hash"})
        },
        indexes = {
                @Index(name = "idx_poll_guest_participants_poll", columnList = "poll_id"),
                @Index(name = "idx_poll_guest_participants_linked_user", columnList = "linked_user_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollGuestParticipant extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Poll poll;

    @Column(nullable = false, length = 20)
    private String nickname;

    /** 브라우저에만 원문을 주고 DB에는 SHA-256 hex만 저장한다. */
    @Column(name = "edit_token_hash", nullable = false, length = 64)
    private String editTokenHash;

    /** 앱 계정에 연결된 공개 투표 응답. 그룹 멤버십과는 별개다. */
    @Column(name = "linked_user_id")
    private Long linkedUserId;

    /** 웹 응답을 앱 계정으로 넘길 때만 쓰는 단기 일회용 코드의 SHA-256 해시. */
    @Column(name = "handoff_code_hash", length = 64)
    private String handoffCodeHash;

    @Column(name = "handoff_expires_at")
    private java.time.Instant handoffExpiresAt;

    @Builder
    private PollGuestParticipant(Poll poll, String nickname, String editTokenHash) {
        this.poll = poll;
        this.nickname = nickname;
        this.editTokenHash = editTokenHash;
    }

    public Long getPollId() {
        return poll.getId();
    }

    public void rename(String nickname) {
        this.nickname = nickname;
    }

    public void issueHandoff(String codeHash, java.time.Instant expiresAt) {
        this.handoffCodeHash = codeHash;
        this.handoffExpiresAt = expiresAt;
    }

    public boolean hasActiveHandoff(String codeHash, java.time.Instant now) {
        return handoffCodeHash != null
                && handoffCodeHash.equals(codeHash)
                && handoffExpiresAt != null
                && handoffExpiresAt.isAfter(now);
    }

    public void claim(Long userId) {
        this.linkedUserId = userId;
    }
}

package com.gatherit.api.group.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "groups_")  // `groups` is reserved in MySQL 8 → 충돌 회피용 underscore
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Group extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 캘린더 라벨 색상 키 (pink/green/team/family/club/cert) */
    @Enumerated(EnumType.STRING)
    @Column(name = "color_key", nullable = false, length = 20)
    private GroupColor colorKey;

    /** 생성자 user_id (소유자). owner 권한 체크에 사용. */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * 개인 캘린더 그룹 — 회원가입 시 자동 생성되는 "내 캘린더".
     * - 초대 토큰 발급 차단
     * - 멤버 추가/탈퇴/삭제 차단 (생성자 1인 고정)
     * - UI 에서 별도 표시 가능
     */
    @Column(name = "personal", nullable = false)
    private boolean personal;

    /** 그룹 사진 삭제 권한 — 기본 ANYONE. owner 가 그룹 정보 수정에서 변경. */
    @Enumerated(EnumType.STRING)
    @Column(name = "photo_delete_policy", nullable = false, length = 20)
    private PhotoDeletePolicy photoDeletePolicy = PhotoDeletePolicy.ANYONE;

    /** 멤버 초대 권한 — 기본 ANYONE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "invite_policy", nullable = false, length = 20)
    private GroupMemberPolicy invitePolicy = GroupMemberPolicy.ANYONE;

    /** 일정 생성 권한 — 기본 ANYONE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_create_policy", nullable = false, length = 20)
    private GroupMemberPolicy eventCreatePolicy = GroupMemberPolicy.ANYONE;

    /** 투표 생성 권한 — 기본 ANYONE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "poll_create_policy", nullable = false, length = 20)
    private GroupMemberPolicy pollCreatePolicy = GroupMemberPolicy.ANYONE;

    /** 웹 투표를 계정에 연결한 사용자가 별도 초대 없이 이 그룹에 직접 가입할 수 있는지. */
    @Column(name = "public_poll_join_allowed", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean publicPollJoinAllowed = true;

    @Builder
    private Group(String name, GroupColor colorKey, Long createdBy, boolean personal,
                  PhotoDeletePolicy photoDeletePolicy,
                  GroupMemberPolicy invitePolicy,
                  GroupMemberPolicy eventCreatePolicy,
                  GroupMemberPolicy pollCreatePolicy,
                  Boolean publicPollJoinAllowed) {
        this.name = name;
        this.colorKey = colorKey;
        this.createdBy = createdBy;
        this.personal = personal;
        this.photoDeletePolicy = photoDeletePolicy != null ? photoDeletePolicy : PhotoDeletePolicy.ANYONE;
        this.invitePolicy = invitePolicy != null ? invitePolicy : GroupMemberPolicy.ANYONE;
        this.eventCreatePolicy = eventCreatePolicy != null ? eventCreatePolicy : GroupMemberPolicy.ANYONE;
        this.pollCreatePolicy = pollCreatePolicy != null ? pollCreatePolicy : GroupMemberPolicy.ANYONE;
        this.publicPollJoinAllowed = publicPollJoinAllowed == null || publicPollJoinAllowed;
    }

    public boolean isOwner(Long userId) {
        return this.createdBy.equals(userId);
    }

    /** 소유권 양도 — createdBy(=owner) 를 새 멤버로 변경. */
    public void changeOwner(Long newOwnerId) {
        this.createdBy = newOwnerId;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void recolor(GroupColor color) {
        this.colorKey = color;
    }

    public void setPhotoDeletePolicy(PhotoDeletePolicy policy) {
        if (policy != null) this.photoDeletePolicy = policy;
    }

    public void setInvitePolicy(GroupMemberPolicy policy) {
        if (policy != null) this.invitePolicy = policy;
    }

    public void setEventCreatePolicy(GroupMemberPolicy policy) {
        if (policy != null) this.eventCreatePolicy = policy;
    }

    public void setPollCreatePolicy(GroupMemberPolicy policy) {
        if (policy != null) this.pollCreatePolicy = policy;
    }

    public void setPublicPollJoinAllowed(boolean allowed) {
        this.publicPollJoinAllowed = allowed;
    }
}

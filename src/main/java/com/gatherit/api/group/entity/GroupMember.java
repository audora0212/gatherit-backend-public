package com.gatherit.api.group.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * 그룹 멤버십 — 복합키 (group_id, user_id).
 * @ManyToOne 매핑 안 쓰고 Long 외래키로 보관: 다른 도메인 복잡한 join fetch 시 유연성 ↑.
 */
@Entity
@Getter
@Table(
    name = "group_members",
    indexes = {
        @Index(name = "idx_group_members_user", columnList = "user_id"),
    }
)
@IdClass(GroupMember.PK.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupMember extends BaseEntity {

    @Id
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    /**
     * 사용자별 그룹 색 — 그룹 페이지/일정 색이 모두 이 값 사용. 다른 멤버에게 영향 X.
     * nullable: 기존 row 호환 (마이그 안 거친 row 는 null → service 에서 team 으로 fallback).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "color_key", length = 20)
    private GroupColor colorKey;

    @Builder
    private GroupMember(Long groupId, Long userId, MemberRole role, GroupColor colorKey) {
        this.groupId = groupId;
        this.userId = userId;
        this.role = role;
        this.colorKey = colorKey;
    }

    public boolean isOwner() {
        return role == MemberRole.OWNER;
    }

    public void changeRole(MemberRole newRole) {
        this.role = newRole;
    }

    public void recolor(GroupColor color) {
        this.colorKey = color;
    }

    @NoArgsConstructor
    public static class PK implements Serializable {
        private Long groupId;
        private Long userId;

        public PK(Long groupId, Long userId) {
            this.groupId = groupId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(groupId, pk.groupId) && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, userId);
        }
    }
}

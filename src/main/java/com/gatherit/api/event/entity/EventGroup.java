package com.gatherit.api.event.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * 일정 ↔ 그룹 N:M 매핑. 한 일정이 여러 그룹에 동시 공유.
 */
@Entity
@Getter
@Table(
    name = "event_groups",
    indexes = {
        @Index(name = "idx_event_groups_group", columnList = "group_id"),
    }
)
@IdClass(EventGroup.PK.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventGroup {

    @Id
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Id
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Builder
    private EventGroup(Long eventId, Long groupId) {
        this.eventId = eventId;
        this.groupId = groupId;
    }

    @NoArgsConstructor
    public static class PK implements Serializable {
        private Long eventId;
        private Long groupId;

        public PK(Long eventId, Long groupId) {
            this.eventId = eventId;
            this.groupId = groupId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(eventId, pk.eventId) && Objects.equals(groupId, pk.groupId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, groupId);
        }
    }
}

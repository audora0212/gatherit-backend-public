package com.gatherit.api.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * 사용자 × 슬롯 가용 응답. PK (poll_id, slot_id, user_id).
 */
@Entity
@Getter
@Table(name = "poll_votes",
    indexes = {
        @Index(name = "idx_poll_votes_poll_user", columnList = "poll_id, user_id"),
    })
@IdClass(PollVote.PK.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollVote {

    @Id
    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Id
    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private boolean available;

    @Builder
    private PollVote(Long pollId, Long slotId, Long userId, boolean available) {
        this.pollId = pollId;
        this.slotId = slotId;
        this.userId = userId;
        this.available = available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @NoArgsConstructor
    public static class PK implements Serializable {
        private Long pollId;
        private Long slotId;
        private Long userId;

        public PK(Long pollId, Long slotId, Long userId) {
            this.pollId = pollId;
            this.slotId = slotId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(pollId, pk.pollId)
                && Objects.equals(slotId, pk.slotId)
                && Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pollId, slotId, userId);
        }
    }
}

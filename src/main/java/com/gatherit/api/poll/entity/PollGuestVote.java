package com.gatherit.api.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** 비회원 참여자의 '가능함' 응답. 행이 없으면 해당 슬롯은 불가능이다. */
@Entity
@Getter
@Table(name = "poll_guest_votes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_poll_guest_vote_participant_slot",
                columnNames = {"participant_id", "slot_id"}),
        indexes = {
                @Index(name = "idx_poll_guest_votes_participant", columnList = "participant_id"),
                @Index(name = "idx_poll_guest_votes_slot", columnList = "slot_id")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollGuestVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PollGuestParticipant participant;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Builder
    private PollGuestVote(PollGuestParticipant participant, Long slotId) {
        this.participant = participant;
        this.slotId = slotId;
    }

    public Long getPollId() {
        return participant.getPollId();
    }
}

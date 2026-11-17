package com.gatherit.api.poll.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@Table(name = "poll_slots",
    indexes = {
        @Index(name = "idx_poll_slots_poll", columnList = "poll_id"),
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PollSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "poll_id", nullable = false)
    private Long pollId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Builder
    private PollSlot(Long pollId, Instant startAt, Instant endAt) {
        this.pollId = pollId;
        this.startAt = startAt;
        this.endAt = endAt;
    }
}

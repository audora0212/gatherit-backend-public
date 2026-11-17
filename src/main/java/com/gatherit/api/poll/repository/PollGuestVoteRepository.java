package com.gatherit.api.poll.repository;

import com.gatherit.api.poll.entity.PollGuestVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PollGuestVoteRepository extends JpaRepository<PollGuestVote, Long> {

    @Query("SELECT v FROM PollGuestVote v JOIN FETCH v.participant p " +
            "WHERE p.poll.id = :pollId ORDER BY p.createdAt ASC")
    List<PollGuestVote> findAllByPollId(@Param("pollId") Long pollId);

    @Query("SELECT v FROM PollGuestVote v JOIN FETCH v.participant p " +
            "WHERE p.poll.id IN :pollIds ORDER BY p.createdAt ASC")
    List<PollGuestVote> findAllByPollIdIn(@Param("pollIds") Collection<Long> pollIds);

    @Query("SELECT v FROM PollGuestVote v WHERE v.participant.id = :participantId")
    List<PollGuestVote> findAllByParticipantId(@Param("participantId") Long participantId);

    @Modifying
    @Query("DELETE FROM PollGuestVote v WHERE v.participant.id = :participantId")
    void deleteAllByParticipantId(@Param("participantId") Long participantId);
}

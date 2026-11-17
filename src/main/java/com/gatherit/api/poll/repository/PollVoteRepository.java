package com.gatherit.api.poll.repository;

import com.gatherit.api.poll.entity.PollVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PollVoteRepository extends JpaRepository<PollVote, PollVote.PK> {

    List<PollVote> findAllByPollId(Long pollId);

    /** 그룹 투표 목록 일괄 조립용 (P2-9). */
    List<PollVote> findAllByPollIdIn(Collection<Long> pollIds);

    List<PollVote> findAllByPollIdAndUserId(Long pollId, Long userId);

    void deleteAllByPollId(Long pollId);

    void deleteByPollIdAndUserId(Long pollId, Long userId);

    /** 회원탈퇴 cleanup — 사용자의 모든 폴 투표 삭제. */
    void deleteAllByUserId(Long userId);

    /** 한 번이라도 응답한 user id distinct — POLL_CONFIRMED 알림 대상자 lookup. */
    @Query("SELECT DISTINCT v.userId FROM PollVote v WHERE v.pollId = :pollId")
    List<Long> findRespondentUserIds(Long pollId);
}

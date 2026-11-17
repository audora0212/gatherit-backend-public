package com.gatherit.api.poll.repository;

import com.gatherit.api.poll.entity.PollSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PollSlotRepository extends JpaRepository<PollSlot, Long> {

    List<PollSlot> findAllByPollIdOrderByStartAtAsc(Long pollId);

    /** 그룹 투표 목록 일괄 조립용 (P2-9). */
    List<PollSlot> findAllByPollIdInOrderByStartAtAsc(Collection<Long> pollIds);

    void deleteAllByPollId(Long pollId);
}

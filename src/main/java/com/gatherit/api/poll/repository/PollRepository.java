package com.gatherit.api.poll.repository;

import com.gatherit.api.poll.entity.Poll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PollRepository extends JpaRepository<Poll, Long> {

    List<Poll> findAllByGroupIdOrderByCreatedAtDesc(Long groupId);

    Optional<Poll> findByPublicShareToken(String publicShareToken);

    boolean existsByPublicShareToken(String publicShareToken);

    /** Cleanup cron 용 — 미확정 폴 전부. service 에서 마지막 슬롯 endAt 비교로 dead 판정. */
    List<Poll> findAllByConfirmedSlotIdIsNull();

    /** 특정 사용자가 만든 모든 폴 — 회원탈퇴 cleanup 용. */
    List<Poll> findByCreatedBy(Long createdBy);

    /** 떠나기/강퇴 cleanup — 본인이 그 그룹에 만든 폴. */
    List<Poll> findByCreatedByAndGroupId(Long createdBy, Long groupId);

    /** 그룹 삭제 cascade — 그 그룹의 모든 폴. */
    List<Poll> findAllByGroupId(Long groupId);

    /** 그룹 탈퇴로 자동 삭제된 일정의 연결을 비워 재등록을 허용할 때 사용. 직접 삭제는 연결 ID를 유지한다. */
    List<Poll> findByLinkedEventIdIn(java.util.Collection<Long> eventIds);

    /** 마감 알림 대상 — 미확정 + 아직 발의자에게 마감 알림 안 보냄 + 마감 시각 지남. */
    List<Poll> findAllByConfirmedSlotIdIsNullAndDeadlineNotifiedFalseAndDeadlineAtBefore(Instant now);

    /**
     * 내가 아직 응답 안 한 "진행 중" 투표 수 — 잠금화면 라이브 카드 알림 줄용.
     * 진행 중 = 미확정 + 마감 전. 내 그룹 소속 투표만.
     */
    @Query("""
            SELECT COUNT(p) FROM Poll p
            WHERE p.confirmedSlotId IS NULL
              AND p.deadlineAt > :now
              AND p.groupId IN (SELECT gm.groupId FROM GroupMember gm WHERE gm.userId = :userId)
              AND NOT EXISTS (SELECT 1 FROM PollVote v WHERE v.pollId = p.id AND v.userId = :userId)
            """)
    long countPendingForUser(@Param("userId") Long userId, @Param("now") Instant now);
}

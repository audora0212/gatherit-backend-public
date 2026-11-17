package com.gatherit.api.event.repository;

import com.gatherit.api.event.entity.DdayPin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DdayPinRepository extends JpaRepository<DdayPin, Long> {

    List<DdayPin> findAllByUserId(Long userId);

    Optional<DdayPin> findByUserIdAndEventId(Long userId, Long eventId);

    /** 일정 하드 삭제 시 모든 유저의 핀 정리 (작성자 삭제/개인캘린더 초기화 cascade). */
    void deleteAllByEventId(Long eventId);

    void deleteAllByEventIdIn(Collection<Long> eventIds);

    /** 회원 탈퇴 시 본인 핀 전부 정리. */
    void deleteAllByUserId(Long userId);

    List<DdayPin> findAllByUserIdAndPrimaryTrue(Long userId);
}

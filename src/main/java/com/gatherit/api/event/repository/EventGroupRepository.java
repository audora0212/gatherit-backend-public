package com.gatherit.api.event.repository;

import com.gatherit.api.event.entity.EventGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventGroupRepository extends JpaRepository<EventGroup, EventGroup.PK> {

    List<EventGroup> findAllByEventId(Long eventId);

    List<EventGroup> findAllByEventIdIn(List<Long> eventIds);

    List<EventGroup> findAllByGroupId(Long groupId);

    void deleteAllByEventId(Long eventId);

    boolean existsByEventIdAndGroupIdIn(Long eventId, List<Long> groupIds);

    /** 다중그룹 일정에서 특정 그룹 연결만 제거 — 떠나기 cleanup. */
    void deleteByEventIdAndGroupId(Long eventId, Long groupId);

    /** 그룹 삭제 cascade — 그 그룹의 모든 EventGroup row. */
    void deleteAllByGroupId(Long groupId);
}

package com.gatherit.api.event.repository;

import com.gatherit.api.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * 캘린더 월간 조회 — [from, to) 범위 + 사용자가 속한 그룹들의 일정.
     * EventGroup 으로 join 후 distinct (multi-group 일정 중복 방지).
     * 기존 앱은 이 응답의 RRULE 원본을 그룹 목록/지도에서 원본 날짜로 표시하므로
     * 과거 원본을 추가로 반환하지 않는다.
     */
    @Query("""
        select distinct e from Event e
        where e.id in (
            select eg.eventId from EventGroup eg
            where eg.groupId in (
                select gm.groupId from com.gatherit.api.group.entity.GroupMember gm where gm.userId = :userId
            )
        )
        and e.startAt < :to and e.endAt >= :from
        order by e.startAt asc
    """)
    List<Event> findVisibleEventsInRange(@Param("userId") Long userId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /**
     * 신규 앱의 명시적 opt-in 범위 조회. 반복 일정은 원본 날짜가 범위 전이어도
     * 이 기간에 회차가 있을 수 있다. 회차/COUNT/UNTIL 판정은 신규 앱이 담당한다.
     */
    @Query("""
        select distinct e from Event e
        where e.id in (
            select eg.eventId from EventGroup eg
            where eg.groupId in (
                select gm.groupId from com.gatherit.api.group.entity.GroupMember gm where gm.userId = :userId
            )
        )
        and e.startAt < :to
        and (e.endAt >= :from or e.rrule is not null)
        order by e.startAt asc
    """)
    List<Event> findVisibleEventsInRangeIncludingRecurringSources(@Param("userId") Long userId,
                                                                    @Param("from") Instant from,
                                                                    @Param("to") Instant to);

    /** 디데이 핀 resolve — 핀된 일정 중 지금도 내게 보이는 것만 (그룹 탈퇴 시 자연 제외). */
    @Query("""
        select distinct e from Event e
        where e.id in :ids and e.id in (
            select eg.eventId from EventGroup eg
            where eg.groupId in (
                select gm.groupId from com.gatherit.api.group.entity.GroupMember gm where gm.userId = :userId
            )
        )
    """)
    List<Event> findVisibleByIdIn(@Param("userId") Long userId, @Param("ids") java.util.Collection<Long> ids);

    /** MY 탭 stats — 사용자가 볼 수 있는 그룹들의 미래(startAt > now) 이벤트 수. */
    @Query("""
        select count(distinct e.id) from Event e
        where e.id in (
            select eg.eventId from EventGroup eg
            where eg.groupId in (
                select gm.groupId from com.gatherit.api.group.entity.GroupMember gm where gm.userId = :userId
            )
        )
        and e.startAt > :now
    """)
    long countUpcomingForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** 특정 원본의 그림자들 — 본인이 만든 것만 (detail 화면용). */
    List<Event> findByShadowOfEventIdAndCreatedBy(Long shadowOfEventId, Long createdBy);

    /** 특정 사용자가 만든, 주어진 원본 일정들의 그림자 — 그룹 떠남/강퇴 시 cleanup 용. */
    List<Event> findByCreatedByAndShadowOfEventIdIn(Long createdBy, List<Long> shadowOfEventIds);

    /** 특정 사용자가 만든 모든 일정 (원본 + 그림자) — 회원탈퇴 cleanup 용. */
    List<Event> findByCreatedBy(Long createdBy);

    /** 특정 원본의 모든 그림자 (작성자 무관) — 회원탈퇴 시 원본 cascade. */
    List<Event> findByShadowOfEventId(Long shadowOfEventId);

    /** 시리즈의 모든 오버라이드(단일 수정된 회차) — 시리즈 삭제/절단 시 cascade/정리용. */
    List<Event> findByRecurrenceParentId(Long recurrenceParentId);

    /** 특정 시리즈+회차의 오버라이드 — "이 회차만 수정" 재편집 시 기존 오버라이드 교체용. */
    List<Event> findByRecurrenceParentIdAndRecurrenceId(Long recurrenceParentId, Instant recurrenceId);

    /** 주어진 폴들에서 파생된 일정 (작성자 무관) — 폴 삭제 시 살아남는 일정의 fromPollId detach 용. */
    List<Event> findByFromPollIdIn(java.util.Collection<Long> pollIds);

    /**
     * 본인이 그 그룹에 만든 origin 일정 (그림자 아님). 떠나기/강퇴 cleanup 용.
     * EventGroup 조인 — 단일/다중 그룹 모두 매칭.
     */
    @Query("""
        select distinct e from Event e
        where e.createdBy = :userId
          and e.shadowOfEventId is null
          and e.id in (
            select eg.eventId from EventGroup eg where eg.groupId = :groupId
          )
    """)
    List<Event> findOriginEventsByCreatedByAndGroupId(@Param("userId") Long userId,
                                                     @Param("groupId") Long groupId);

    /** 특정 그룹의 모든 일정 (iCal 그룹별 export 용) — EventGroup 조인, startAt 오름차순. */
    @Query("""
        select distinct e from Event e
        where e.id in (select eg.eventId from EventGroup eg where eg.groupId = :groupId)
        order by e.startAt asc
    """)
    List<Event> findAllByGroupId(@Param("groupId") Long groupId);

    /** 선택된 그룹들의 모든 일정 (iCal 선택 export 용) — 호출 전 멤버십 교집합 필터 필수. */
    @Query("""
        select distinct e from Event e
        where e.id in (select eg.eventId from EventGroup eg where eg.groupId in :groupIds)
        order by e.startAt asc
    """)
    List<Event> findAllByGroupIds(@Param("groupIds") java.util.List<Long> groupIds);

    /** 외부 .ics 재import 중복 방지 — 같은 사용자 + 같은 원본 UID 존재 여부. */
    boolean existsByCreatedByAndImportUid(Long createdBy, String importUid);

    /** 사용자가 만든 총 일정 수 — .ics import 총량 cap 에 사용. */
    long countByCreatedBy(Long createdBy);

    /** 일정 검색 — 가시성(내 그룹) + 원본만 + title/location/memo/placeName 키워드. 최근(startAt desc)순. */
    @Query("""
        select distinct e from Event e
        where e.id in (
            select eg.eventId from EventGroup eg
            where eg.groupId in (
                select gm.groupId from com.gatherit.api.group.entity.GroupMember gm where gm.userId = :userId
            )
        )
        and e.shadowOfEventId is null
        and (lower(e.title) like lower(concat('%', :q, '%'))
             or lower(e.location) like lower(concat('%', :q, '%'))
             or lower(e.memo) like lower(concat('%', :q, '%'))
             or lower(e.placeName) like lower(concat('%', :q, '%')))
        order by e.startAt desc
    """)
    List<Event> searchVisible(@Param("userId") Long userId, @Param("q") String q);
}

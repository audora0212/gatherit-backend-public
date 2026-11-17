package com.gatherit.api.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** 현재 노출 기간인 공지 — 진입 모달용. startsAt <= now < endsAt(or 무기한). */
    @Query("""
        select a from Announcement a
        where a.startsAt <= :now and (a.endsAt is null or a.endsAt > :now)
        order by a.startsAt desc
    """)
    List<Announcement> findActive(@Param("now") Instant now);

    /** 최근 공지 50개 — MY 공지 리스트(archive)용. */
    List<Announcement> findTop50ByOrderByStartsAtDesc();
}

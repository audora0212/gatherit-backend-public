package com.gatherit.api.popup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PopupRepository extends JpaRepository<Popup, Long> {

    /** 현재 노출 대상 — enabled + 기간 내. 버전 타깃/dismiss 규칙은 앱이 판정. */
    @Query("SELECT p FROM Popup p WHERE p.enabled = true AND p.startsAt <= :now " +
           "AND (p.endsAt IS NULL OR p.endsAt > :now) ORDER BY p.startsAt DESC")
    List<Popup> findActive(@Param("now") Instant now);

    /** 지난 공지 아카이브(MY 공지사항) — 노출 시작된 팝업. 회수(꺼짐)는 제외, 기간 종료는 포함. */
    List<Popup> findTop50ByEnabledTrueAndStartsAtLessThanEqualOrderByStartsAtDesc(Instant now);
}

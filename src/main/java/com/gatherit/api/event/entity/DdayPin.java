package com.gatherit.api.event.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 디데이 핀 — "이 일정을 내 디데이로" (v1.4 B).
 * 일정의 속성이 아니라 유저 개인 설정: 같은 그룹 일정이라도 내가 꽂아야 내 캘린더 헤더/잠금화면 위젯에 뜬다.
 * 노출 대상 선정(가장 가까운 미래 1건)/D-N 계산은 앱이 담당, 서버는 핀 저장/정리만.
 *
 * 정리 경로: 일정 삭제/개인캘린더 초기화 = 즉시 cascade / 그룹 탈퇴 등 가시성 상실 = 목록 조회 시 lazy
 * (다른 그룹으로 여전히 보이는 일정의 핀은 살아야 해서 탈퇴 시점엔 판단 불가).
 */
@Entity
@Getter
@Table(name = "dday_pins",
        uniqueConstraints = @UniqueConstraint(name = "uq_dday_user_event", columnNames = {"user_id", "event_id"}),
        indexes = @Index(name = "idx_dday_event", columnList = "event_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DdayPin extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 대표 디데이 — 접힌 카드/위젯에 우선 노출. 유저당 최대 1개(설정 시 기존 대표 해제). */
    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    public DdayPin(Long userId, Long eventId) {
        this.userId = userId;
        this.eventId = eventId;
        this.primary = false;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }
}

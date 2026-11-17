package com.gatherit.api.popup;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 인앱 공지 모달(팝업). MY 공지사항과 별개 기능.
 *
 * 노출 = enabled AND [startsAt, endsAt) AND 버전 타깃 AND 기기 로컬 dismiss 규칙:
 *  - rule ONCE  : 유저당 1회. 닫으면 다시 안 뜸 (업데이트 What's New, 1회성 안내)
 *  - rule DAILY : 하루 1회(KST 날짜). 노출 기간 동안 매일 (점검 예고 등)
 * dismiss 상태는 앱이 popup id 단위로 로컬 저장 — BE 는 규칙/기간/타깃만 내려준다.
 */
@Entity
@Getter
@Table(name = "popups")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Popup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** ONCE(1회) / DAILY(하루 1회). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PopupRule rule;

    /** 노출 시작 (이 시각부터). */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** 노출 종료 (null = 무기한). */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** 이 버전 "미만" 사용자에게만 노출 (예: "1.4.0"). null = 전체. 비교는 앱이 수행. */
    @Column(name = "target_below_version", length = 20)
    private String targetBelowVersion;

    /** 운영자 on/off 스위치 — 기간 안이어도 끌 수 있게. */
    @Column(nullable = false)
    private boolean enabled;

    @Builder
    private Popup(String title, String body, PopupRule rule, Instant startsAt, Instant endsAt,
                  String targetBelowVersion, boolean enabled) {
        this.title = title;
        this.body = body;
        this.rule = rule;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.targetBelowVersion = targetBelowVersion;
        this.enabled = enabled;
    }
}

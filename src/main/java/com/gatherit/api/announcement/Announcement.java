package com.gatherit.api.announcement;

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
 * 전체 사용자에게 노출하는 읽기 전용 공지.
 * 노출 기간 [startsAt, endsAt) — endsAt null = 무기한. 점검 공지 등 한시적이면 endsAt set.
 */
@Entity
@Getter
@Table(name = "announcements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnouncementType type;

    /** 노출 시작 (이 시각부터 active). 보통 생성 시각. */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** 노출 종료 (null = 무기한). 점검 공지 등 한시적일 때만 set. */
    @Column(name = "ends_at")
    private Instant endsAt;

    @Builder
    private Announcement(String title, String body, AnnouncementType type, Instant startsAt, Instant endsAt) {
        this.title = title;
        this.body = body;
        this.type = type;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }
}

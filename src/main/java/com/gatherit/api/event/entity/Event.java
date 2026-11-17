package com.gatherit.api.event.entity;

import com.gatherit.api.common.entity.BaseEntity;
import com.gatherit.api.group.entity.GroupColor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Entity
@Getter
@Table(
    name = "events",
    indexes = {
        @Index(name = "idx_events_start_at", columnList = "start_at"),
        @Index(name = "idx_events_created_by", columnList = "created_by"),
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /**
     * 하루종일(All-day) 일정 여부. true = 시간 picker 없이 날짜만 의미.
     * 저장 규약: startAt = 그 날 00:00:00 KST, endAt = 같은 날 23:59:59 KST (FE 정규화).
     * ddl-auto: update 가 기존 row 에 default false 적용하도록 columnDefinition 명시.
     */
    @Column(name = "all_day", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    private boolean allDay;

    /**
     * 일반 일정용 알림 override (분 단위). null = 사용자 전역 default (prefs.leadTimeMinutes) 따름,
     * 0 = 명시적 끄기, 양수 = N분 전. 종일 일정은 이 필드 무시.
     */
    @Column(name = "reminder_minutes_before")
    private Integer reminderMinutesBefore;

    /**
     * 종일 일정용 알림 override (일 단위). null = 전역 default (prefs.allDayReminderDays) 따름,
     * -1 = 명시적 끄기, 0/1/7 = 당일/1일 전/1주일 전. 일반 일정은 이 필드 무시.
     */
    @Column(name = "reminder_all_day_days")
    private Integer reminderAllDayDays;

    @Column(length = 200)
    private String location;

    @Column(length = 1000)
    private String memo;

    /** 위도 (지도 picker 선택 시). null = 텍스트 location 만 있고 좌표 없음. */
    @Column(name = "latitude")
    private Double latitude;

    /** 경도. null = 텍스트 location 만 있고 좌표 없음. */
    @Column(name = "longitude")
    private Double longitude;

    /** POI 이름 (예: "스타벅스 강남역점"). 카카오 로컬 API place 검색 결과. */
    @Column(name = "place_name", length = 100)
    private String placeName;

    /** 도로명 주소 (예: "서울 강남구 강남대로 ..."). 카카오 reverse geocode 결과. */
    @Column(name = "road_address", length = 200)
    private String roadAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "color_key", length = 20)
    private GroupColor colorKey;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * 그림자 일정의 원본 Event ID. null = 원본, set = 그림자.
     * 그림자는 본인이 만든 사람의 캘린더에서 원본도 보이면 dedup 됨 (findInRange).
     * 시간/장소/메모 등은 의미 없음 — 그룹/라벨만 의미 있음.
     */
    @Column(name = "shadow_of_event_id")
    private Long shadowOfEventId;

    /**
     * 폴 확정 후 "내 일정에 추가" 흐름으로 만들어진 일정이면 해당 폴 ID. null = 일반(직접) 생성.
     * - 폴 출처 일정: groupIds 변경 불가 (그림자로만 다른 그룹에 알림)
     * - 일반 일정: groupIds 직접 수정 가능 (체크박스로)
     */
    @Column(name = "from_poll_id")
    private Long fromPollId;

    /**
     * 반복 규칙 — iCalendar RFC 5545 의 RRULE 문자열 (예: "FREQ=WEEKLY;BYDAY=MO,WE,FR").
     * null = 단일 일정 (1회). 클라이언트가 expansion 하여 캘린더/위젯에 instance 표시.
     */
    @Column(name = "rrule", length = 200)
    private String rrule;

    /** 외부 .ics import 로 들어온 일정의 원본 UID — 재import 중복 방지용. 일반 생성 일정은 null. */
    @Column(name = "import_uid", length = 255)
    private String importUid;

    /**
     * 반복 예외일 (RFC5545 EXDATE) — 이 시리즈에서 제외된 회차의 시작 Instant 목록, 쉼표 구분 ISO-8601.
     * null/빈 = 예외 없음. "이 회차만 삭제" 및 "이 회차만 수정"(원본 회차 제외) 에서 채워진다.
     * (대량화 시 자식 테이블로 승격 — v1 은 rrule 과 동일하게 단일 문자열 컬럼.)
     */
    @Column(name = "exdates", length = 2000)
    private String exdates;

    /**
     * 오버라이드(단일 수정된 회차) 가 속한 시리즈 Event ID. null = 시리즈 원본 또는 일반 일정.
     * set 이면 이 Event 는 recurrenceId 회차를 대체하는 독립 인스턴스(rrule=null).
     */
    @Column(name = "recurrence_parent_id")
    private Long recurrenceParentId;

    /** 오버라이드가 대체하는 원본 회차의 시작 Instant (RFC5545 RECURRENCE-ID). null = 오버라이드 아님. */
    @Column(name = "recurrence_id")
    private Instant recurrenceId;

    @Builder
    private Event(String title, Instant startAt, Instant endAt, String location, String memo,
                  GroupColor colorKey, Long createdBy, Long shadowOfEventId, Long fromPollId, String rrule,
                  Double latitude, Double longitude, String placeName, String roadAddress, boolean allDay,
                  Integer reminderMinutesBefore, Integer reminderAllDayDays, String importUid,
                  Long recurrenceParentId, Instant recurrenceId) {
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.location = location;
        this.memo = memo;
        this.colorKey = colorKey;
        this.createdBy = createdBy;
        this.shadowOfEventId = shadowOfEventId;
        this.fromPollId = fromPollId;
        this.rrule = rrule;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeName = placeName;
        this.roadAddress = roadAddress;
        this.allDay = allDay;
        this.reminderMinutesBefore = reminderMinutesBefore;
        this.reminderAllDayDays = reminderAllDayDays;
        this.importUid = importUid;
        this.recurrenceParentId = recurrenceParentId;
        this.recurrenceId = recurrenceId;
    }

    // ─── 반복 예외/오버라이드 (v1.5.2 EXDATE) ────────────────────────────────

    /** 제외된 회차 시작 Instant 목록 (정렬). 없으면 빈 리스트. */
    public List<Instant> getExdatesList() {
        if (exdates == null || exdates.isBlank()) return List.of();
        List<Instant> out = new ArrayList<>();
        for (String s : exdates.split(",")) {
            if (!s.isBlank()) out.add(Instant.parse(s.trim()));
        }
        return out;
    }

    /** 예외 회차 추가 (정렬/중복 제거). */
    public void addExdate(Instant occurrenceStart) {
        TreeSet<Instant> set = new TreeSet<>(getExdatesList());
        set.add(occurrenceStart);
        this.exdates = set.stream().map(Instant::toString).collect(Collectors.joining(","));
    }

    /** occurrenceStart 이상(inclusive) 예외 제거 — this-and-future 절단 후 무의미해진 예외 정리. */
    public void pruneExdatesFrom(Instant occurrenceStart) {
        List<Instant> kept = getExdatesList().stream()
                .filter(d -> d.isBefore(occurrenceStart))
                .toList();
        this.exdates = kept.isEmpty() ? null
                : kept.stream().map(Instant::toString).collect(Collectors.joining(","));
    }

    public boolean isShadow() {
        return this.shadowOfEventId != null;
    }

    public boolean isFromPoll() {
        return this.fromPollId != null;
    }

    /**
     * 출처 폴이 삭제됐을 때 호출 — fromPollId 끊어 평범한(편집 가능) 일정으로 독립.
     * (예: 폴 발의자가 탈퇴해 폴이 삭제됐지만, 다른 사람이 추가한 이 확정 일정은 유지되는 경우)
     */
    public void detachFromPoll() {
        this.fromPollId = null;
    }

    public boolean isOwner(Long userId) {
        return this.createdBy.equals(userId);
    }

    public void update(String title, Instant startAt, Instant endAt, String location, String memo, GroupColor colorKey) {
        if (title != null) this.title = title;
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (location != null) this.location = location;
        if (memo != null) this.memo = memo;
        if (colorKey != null) this.colorKey = colorKey;
    }

    /** rrule 만 별도 변경 — null 허용 (반복 해제). */
    public void updateRrule(String rrule) {
        this.rrule = rrule;
    }

    /** 그림자는 원본의 시간/반복/예외 회차를 그대로 따른다. 라벨/그룹은 유지한다. */
    public void syncTimingFrom(Event original) {
        this.startAt = original.startAt;
        this.endAt = original.endAt;
        this.rrule = original.rrule;
        this.exdates = original.exdates;
        this.allDay = original.allDay;
    }

    /** 하루종일 토글 — Boolean null = 변경 안 함. */
    public void updateAllDay(Boolean allDay) {
        if (allDay != null) this.allDay = allDay;
    }

    /**
     * 알림 override 갱신. Sentinel 시맨틱:
     *  - minutesBefore: null = 변경 안 함, Integer.MIN_VALUE = default 로 되돌림(엔티티는 null),
     *    그 외 (0 포함) = 그대로 set.
     *  - daysBefore: 동일 패턴.
     */
    public void updateReminder(Integer minutesBefore, Integer daysBefore) {
        if (minutesBefore != null) {
            this.reminderMinutesBefore = minutesBefore == Integer.MIN_VALUE ? null : minutesBefore;
        }
        if (daysBefore != null) {
            this.reminderAllDayDays = daysBefore == Integer.MIN_VALUE ? null : daysBefore;
        }
    }

    /**
     * 위치 좌표/POI 메타 갱신 — 지도 picker 흐름에서 호출.
     * 모든 인자 null = 변경 안 함. 빈 문자열("") = 명시적 해제 (placeName/roadAddress 용).
     * latitude == null && longitude == null 인 경우 좌표 해제 (둘은 항상 함께 set/clear).
     */
    public void updateGeo(Double latitude, Double longitude, String placeName, String roadAddress) {
        if (latitude != null && longitude != null) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
        if (placeName != null) {
            this.placeName = placeName.isEmpty() ? null : placeName;
        }
        if (roadAddress != null) {
            this.roadAddress = roadAddress.isEmpty() ? null : roadAddress;
        }
    }

    /** 좌표 해제 — 사용자가 지도 선택을 취소한 경우. */
    public void clearGeo() {
        this.latitude = null;
        this.longitude = null;
        this.placeName = null;
        this.roadAddress = null;
    }
}

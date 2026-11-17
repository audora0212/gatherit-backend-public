package com.gatherit.api.event.dto;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.group.entity.GroupColor;

import java.time.Instant;
import java.util.List;

public record EventResponse(
        Long id,
        String title,
        Instant startAt,
        Instant endAt,
        String location,
        String memo,
        GroupColor colorKey,
        Long createdBy,
        List<Long> groupIds,
        /** 그림자면 원본 ID, 아니면 null. */
        Long shadowOfEventId,
        /** 폴 확정 후 만들어진 일정이면 폴 ID — FE 가 groupIds 잠금 여부 판정. */
        Long fromPollId,
        /** 그림자일 때 원본의 groupIds — 알릴 그룹 picker 에서 제외 용도. 원본/list 응답에선 빈 list. */
        List<Long> originalGroupIds,
        /** 원본 일정 detail 조회 시 본인이 만든 그림자들 요약. 그림자/list 응답에선 빈 list. */
        List<ShadowSummary> myShadows,
        /** 반복 규칙 — RFC 5545 RRULE 문자열. null = 단일 일정. */
        String rrule,
        /** 위도 — 지도 picker 흐름에서 선택된 좌표. null = 텍스트 location 만. */
        Double latitude,
        /** 경도. */
        Double longitude,
        /** POI 이름 (예: "스타벅스 강남역점"). */
        String placeName,
        /** 도로명 주소. */
        String roadAddress,
        /** 하루종일 일정 여부. */
        boolean allDay,
        /** 일반 일정용 알림 override (분). null = 전역 default 따름. */
        Integer reminderMinutesBefore,
        /** 종일 일정용 알림 override (일). null = 전역 default 따름. */
        Integer reminderAllDayDays,
        /** 일정 생성자 닉네임 — 표시용. 서비스에서 enrich, 못 구하면 null. */
        String creatorNickname,
        /** 일정 생성자 본인 색 — #RRGGBB. null = 클라가 createdBy 해시로 fallback. */
        String creatorColor,
        /** 반복 예외일 — 이 회차들은 전개에서 제외. 클라가 expansion 시 스킵. 빈 리스트 = 예외 없음. */
        List<Instant> exdates,
        /** 오버라이드면 시리즈 원본 ID, 아니면 null. */
        Long recurrenceParentId,
        /** 오버라이드가 대체하는 원본 회차 시작 (RECURRENCE-ID), 아니면 null. */
        Instant recurrenceId
) {
    public record ShadowSummary(
            Long id,
            String title,
            List<Long> groupIds
    ) {}

    /** createdBy → (nickname, color) 매핑 후 채운 복사본 반환. of/ofList 는 null 로 두고 서비스에서 호출. */
    public EventResponse withCreator(String nickname, String color) {
        return new EventResponse(
                id, title, startAt, endAt, location, memo, colorKey, createdBy, groupIds,
                shadowOfEventId, fromPollId, originalGroupIds, myShadows, rrule,
                latitude, longitude, placeName, roadAddress, allDay,
                reminderMinutesBefore, reminderAllDayDays, nickname, color,
                exdates, recurrenceParentId, recurrenceId
        );
    }

    public static EventResponse of(Event e, List<Long> groupIds, GroupColor color, List<Long> originalGroupIds, List<ShadowSummary> myShadows) {
        return new EventResponse(
                e.getId(),
                e.getTitle(),
                e.getStartAt(),
                e.getEndAt(),
                e.getLocation(),
                e.getMemo(),
                color != null ? color : e.getColorKey(),
                e.getCreatedBy(),
                groupIds,
                e.getShadowOfEventId(),
                e.getFromPollId(),
                originalGroupIds,
                myShadows,
                e.getRrule(),
                e.getLatitude(),
                e.getLongitude(),
                e.getPlaceName(),
                e.getRoadAddress(),
                e.isAllDay(),
                e.getReminderMinutesBefore(),
                e.getReminderAllDayDays(),
                null,
                null,
                e.getExdatesList(),
                e.getRecurrenceParentId(),
                e.getRecurrenceId()
        );
    }

    /** 캘린더 list 에서는 originalGroupIds + myShadows 안 채움 (성능). detail 에서만 포함. */
    public static EventResponse ofList(Event e, List<Long> groupIds, GroupColor color) {
        return of(e, groupIds, color, List.of(), List.of());
    }
}

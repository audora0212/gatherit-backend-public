package com.gatherit.api.event.dto;

import com.gatherit.api.group.entity.GroupColor;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record EventUpdateRequest(
        @Size(max = 100) String title,
        Instant startAt,
        Instant endAt,
        @Size(max = 200) String location,
        @Size(max = 1000) String memo,
        GroupColor colorKey,
        /** null 이면 그룹 변경 안 함. 빈 배열은 허용 안 함 (서비스에서 검증). */
        List<Long> groupIds,
        /** 반복 규칙 — null=변경 안 함, ""(빈 문자열)=반복 해제, 그 외=새 RRULE. */
        @Size(max = 200) String rrule,
        /** 위도 — null=변경 안 함. latitude/longitude 는 항상 함께 set/clear. */
        Double latitude,
        /** 경도. */
        Double longitude,
        /** POI 이름 — null=변경 안 함, ""=해제. */
        @Size(max = 100) String placeName,
        /** 도로명 주소. */
        @Size(max = 200) String roadAddress,
        /** 좌표 명시 해제 — true 면 lat/lng/placeName/roadAddress 모두 null 로. */
        Boolean clearGeo,
        /** 하루종일 토글 — null = 변경 안 함, true/false = 갱신. */
        Boolean allDay,
        /**
         * 일반 일정 알림 override 갱신.
         *  - null = 변경 안 함
         *  - Integer.MIN_VALUE = "기본으로 되돌림" sentinel — BE 가 null 로 set
         *  - 그 외(0 포함) = 그 값 그대로 (0=명시적 끄기, N=N분 전)
         */
        Integer reminderMinutesBefore,
        /** 종일 일정 알림 override. 시맨틱은 reminderMinutesBefore 와 동일 (-1=끄기, 0/1/7=당일/1일/1주일). */
        Integer reminderAllDayDays,
        /**
         * 반복 일정 수정 범위 — null/ALL = 시리즈 전체(기존 동작, 하위호환).
         * THIS / THIS_AND_FUTURE 는 rrule 있는 owner 일반 일정에서만 유효.
         */
        EditScope editScope,
        /** 대상 회차의 시작 Instant (RECURRENCE-ID) — editScope 가 THIS/THIS_AND_FUTURE 일 때 필수. */
        Instant occurrenceStart
) {}

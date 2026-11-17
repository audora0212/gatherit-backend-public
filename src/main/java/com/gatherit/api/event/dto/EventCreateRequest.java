package com.gatherit.api.event.dto;

import com.gatherit.api.group.entity.GroupColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record EventCreateRequest(
        @NotBlank @Size(max = 100) String title,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @Size(max = 200) String location,
        @Size(max = 1000) String memo,
        GroupColor colorKey,
        @NotEmpty List<Long> groupIds,
        /** 폴 확정 후 첫 "내 일정에 추가" 흐름에서 전달 — 이미 link 된 폴이면 409. */
        Long pollId,
        /** 그림자 일정 생성 시 원본 Event ID. null = 원본 일정 생성. */
        Long shadowOfEventId,
        /** 반복 규칙 — RFC 5545 RRULE 문자열 (예: "FREQ=WEEKLY;BYDAY=MO"). null = 단일 일정. */
        @Size(max = 200) String rrule,
        /** 위도 — 지도 picker 선택 시. null = 좌표 없음 (텍스트 location 만). */
        Double latitude,
        /** 경도 — 지도 picker 선택 시. */
        Double longitude,
        /** POI 이름 (예: "스타벅스 강남역점"). */
        @Size(max = 100) String placeName,
        /** 도로명 주소. */
        @Size(max = 200) String roadAddress,
        /** 하루종일 일정 — true 면 시간 picker 없는 종일 표시 (startAt/endAt 는 FE 가 KST 00:00/23:59 로 정규화 후 전달). null = false. */
        Boolean allDay,
        /** 일반 일정용 알림 override (분). null = 전역 default 따름, 0 = 끄기, N = N분 전. */
        Integer reminderMinutesBefore,
        /** 종일 일정용 알림 override (일). null = 전역 default 따름, -1 = 끄기, 0/1/7 = 당일/1일/1주일 전. */
        Integer reminderAllDayDays
) {}

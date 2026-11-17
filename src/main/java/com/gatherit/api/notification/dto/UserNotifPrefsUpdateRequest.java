package com.gatherit.api.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 전역 prefs 부분 수정. allOff 또는 leadTime 바뀔 때 호출.
 * 모든 필드 optional — null 이면 변경 X.
 */
public record UserNotifPrefsUpdateRequest(
        Boolean allOff,
        @Min(0) @Max(180) Integer leadTimeMinutes,
        Boolean quietHoursEnabled,
        @Min(0) @Max(23) Integer quietHoursStart,
        @Min(0) @Max(23) Integer quietHoursEnd,
        /** 하루종일 일정 알림 N일 전 9시. -1=off, 0=당일, 1=1일전, 7=1주일전. */
        @Min(-1) @Max(30) Integer allDayReminderDays,
        /** 내가 만든 투표 확정/마감 알림 (발의자 대상). null 이면 변경 X. */
        Boolean pollCompletedEnabled
) {}

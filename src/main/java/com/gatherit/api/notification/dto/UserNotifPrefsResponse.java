package com.gatherit.api.notification.dto;

import com.gatherit.api.notification.entity.UserNotifPrefs;

import java.util.Map;

public record UserNotifPrefsResponse(
        boolean allOff,
        int leadTimeMinutes,
        boolean quietHoursEnabled,
        int quietHoursStart,
        int quietHoursEnd,
        /** 하루종일 일정 알림 N일 전 9시. -1=off, 0=당일, 1=1일전, 7=1주일전. */
        int allDayReminderDays,
        /** 내가 만든 투표 확정/마감 알림 (발의자 대상). */
        boolean pollCompletedEnabled,
        /** key = groupId. 명시 안 된 그룹은 default (모두 ON, eventUpdated 만 OFF). */
        Map<Long, GroupNotifPrefResponse> groupPrefs
) {
    public static UserNotifPrefsResponse from(UserNotifPrefs p, Map<Long, GroupNotifPrefResponse> groupPrefs) {
        return new UserNotifPrefsResponse(
                p.isAllOff(),
                p.getLeadTimeMinutes(),
                p.isQuietHoursEnabled(),
                p.getQuietHoursStart(),
                p.getQuietHoursEnd(),
                p.getAllDayReminderDays(),
                p.isPollCompletedEnabled(),
                groupPrefs);
    }
}

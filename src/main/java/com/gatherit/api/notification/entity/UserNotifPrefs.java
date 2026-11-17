package com.gatherit.api.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저별 알림 prefs — 전역 설정만 보관.
 *
 *  - allOff: master mute. true 면 모든 알림 차단.
 *  - leadTimeMinutes: 일정 N분 전 reminder timing (모든 일정 공통).
 *  - quietHours*: 방해금지 모드. enabled=true 면 start~end 시간대에 push 발송 skip (DB record 는 유지).
 *    start/end 는 hour 단위 (0~23). start > end 이면 자정 wrap (예: 23~8 = 23:00~08:00).
 *
 * type 별 토글 + 그룹 enabled 는 UserGroupNotifPref 에서 그룹 단위로 관리.
 * 본인 캘린더 (personal=true) 는 UserGroupNotifPref row 가 없어도 default(모두 ON) 적용.
 */
@Entity
@Getter
@Table(name = "user_notif_prefs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotifPrefs {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "all_off", nullable = false)
    private boolean allOff = false;

    @Column(name = "lead_time_minutes", nullable = false)
    private int leadTimeMinutes = 10;

    @Column(name = "quiet_hours_enabled", nullable = false)
    private boolean quietHoursEnabled = false;

    /** 방해금지 시작 hour (0~23). default 22 = 22:00. */
    @Column(name = "quiet_hours_start", nullable = false)
    private int quietHoursStart = 22;

    /** 방해금지 종료 hour (0~23). default 8 = 08:00. */
    @Column(name = "quiet_hours_end", nullable = false)
    private int quietHoursEnd = 8;

    /**
     * 하루종일 일정 알림 — N일 전 오전 9시.
     *  -1 = 끄기, 0 = 당일 9시, 1 = 1일 전 9시, 7 = 1주일 전 9시.
     * 일반 일정의 leadTimeMinutes 와 별개 — startAt=00:00 인 종일 일정에 분 단위 reminder 는 부적합.
     * 시각은 9시 고정 (확장은 별도 컬럼 추가). ddl-auto: update 호환 위해 DEFAULT 1 명시.
     */
    @Column(name = "all_day_reminder_days", nullable = false, columnDefinition = "INT NOT NULL DEFAULT 1")
    private int allDayReminderDays = 1;

    /** 내가 만든 투표가 확정/마감됐을 때 알림 (발의자 본인 대상, 전역). */
    @Column(name = "poll_completed_enabled", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean pollCompletedEnabled = true;

    public static UserNotifPrefs defaults(Long userId) {
        UserNotifPrefs p = new UserNotifPrefs();
        p.userId = userId;
        return p;
    }

    public void update(Boolean allOff, Integer leadTimeMinutes,
                       Boolean quietHoursEnabled, Integer quietHoursStart, Integer quietHoursEnd,
                       Integer allDayReminderDays, Boolean pollCompletedEnabled) {
        if (allOff != null) this.allOff = allOff;
        if (leadTimeMinutes != null) this.leadTimeMinutes = leadTimeMinutes;
        if (quietHoursEnabled != null) this.quietHoursEnabled = quietHoursEnabled;
        if (quietHoursStart != null) this.quietHoursStart = clampHour(quietHoursStart);
        if (quietHoursEnd != null) this.quietHoursEnd = clampHour(quietHoursEnd);
        if (allDayReminderDays != null) this.allDayReminderDays = clampAllDayDays(allDayReminderDays);
        if (pollCompletedEnabled != null) this.pollCompletedEnabled = pollCompletedEnabled;
    }

    /** -1 (off) ~ 30 (1달 전) 범위. UI 는 -1/0/1/7 만 노출. */
    private static int clampAllDayDays(int d) {
        if (d < -1) return -1;
        if (d > 30) return 30;
        return d;
    }

    /** 주어진 hour 가 방해금지 윈도우 안인지. start==end 면 always false. */
    public boolean isQuietAt(int hour) {
        if (!quietHoursEnabled) return false;
        if (quietHoursStart == quietHoursEnd) return false;
        if (quietHoursStart < quietHoursEnd) {
            return hour >= quietHoursStart && hour < quietHoursEnd;
        }
        // wrap (예: 22 ~ 8) — 22~23 또는 0~7 이면 quiet
        return hour >= quietHoursStart || hour < quietHoursEnd;
    }

    private static int clampHour(int h) {
        if (h < 0) return 0;
        if (h > 23) return 23;
        return h;
    }
}

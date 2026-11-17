package com.gatherit.api.notification.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 그룹 단위 알림 prefs.
 *
 *  - enabled: 그룹 master. false 면 그룹 관련 알림 전부 차단 (8개 토글 무관).
 *  - 8 type: 그룹 알림별 on/off (default 는 eventUpdated 만 false, 나머지 true).
 *
 * row 없으면 모두 default — UI 가 첫 토글 변경 시 row 생성, 또는 prefsOf 가 자동 생성.
 */
@Entity
@Getter
@Table(name = "user_group_notif_prefs")
@IdClass(UserGroupNotifPref.PK.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGroupNotifPref {

    @Id @Column(name = "user_id") private Long userId;
    @Id @Column(name = "group_id") private Long groupId;

    @Column(nullable = false) private boolean enabled = true;

    @Column(name = "event_created",      nullable = false) private boolean eventCreated      = true;
    @Column(name = "event_updated",      nullable = false) private boolean eventUpdated      = false;
    @Column(name = "poll_created",       nullable = false) private boolean pollCreated       = true;
    @Column(name = "poll_deadline_soon", nullable = false) private boolean pollDeadlineSoon  = true;
    @Column(name = "poll_confirmed",     nullable = false) private boolean pollConfirmed     = true;
    @Column(name = "photo_uploaded",     nullable = false) private boolean photoUploaded     = true;
    @Column(name = "photo_expiring",     nullable = false) private boolean photoExpiring     = true;
    @Column(name = "group_joined_by",    nullable = false) private boolean groupJoinedBy     = true;

    public static UserGroupNotifPref defaults(Long userId, Long groupId) {
        UserGroupNotifPref p = new UserGroupNotifPref();
        p.userId = userId;
        p.groupId = groupId;
        return p;
    }

    /** type 토글 lookup — push 분기에서 사용. enabled=false 면 caller 가 먼저 차단해야 함. */
    public boolean isAllowed(NotificationType type) {
        return switch (type) {
            case EVENT_REMINDER     -> true;             // reminder 는 group 단위 별도 토글 없음 — leadTime + enabled 만
            case EVENT_CREATED      -> eventCreated;
            case EVENT_UPDATED      -> eventUpdated;
            case POLL_CREATED       -> pollCreated;
            case POLL_DEADLINE_SOON -> pollDeadlineSoon;
            case POLL_CONFIRMED     -> pollConfirmed;
            case PHOTO_UPLOADED     -> photoUploaded;
            case PHOTO_EXPIRING     -> photoExpiring;
            case GROUP_JOINED_BY    -> groupJoinedBy;
            case POLL_COMPLETED     -> true; // 전역 prefs(pollCompletedEnabled)로만 판정 — 그룹 scoped 경로 안 탐
        };
    }

    public void update(
            Boolean enabled,
            Boolean eventCreated, Boolean eventUpdated,
            Boolean pollCreated, Boolean pollDeadlineSoon, Boolean pollConfirmed,
            Boolean photoUploaded, Boolean photoExpiring, Boolean groupJoinedBy) {
        if (enabled         != null) this.enabled         = enabled;
        if (eventCreated    != null) this.eventCreated    = eventCreated;
        if (eventUpdated    != null) this.eventUpdated    = eventUpdated;
        if (pollCreated     != null) this.pollCreated     = pollCreated;
        if (pollDeadlineSoon!= null) this.pollDeadlineSoon= pollDeadlineSoon;
        if (pollConfirmed   != null) this.pollConfirmed   = pollConfirmed;
        if (photoUploaded   != null) this.photoUploaded   = photoUploaded;
        if (photoExpiring   != null) this.photoExpiring   = photoExpiring;
        if (groupJoinedBy   != null) this.groupJoinedBy   = groupJoinedBy;
    }

    @EqualsAndHashCode
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private Long userId;
        private Long groupId;
    }
}

package com.gatherit.api.notification.entity;

/**
 * 알림 종류 — FE notif-prefs 키와 1:1 매칭.
 *
 * 로컬(클라 schedule, 오프라인 OK):
 *  - EVENT_REMINDER     : 일정 N분 전
 *  - PHOTO_EXPIRING     : 본인 사진 만료 1일 전
 *  - POLL_DEADLINE_SOON : 폴 마감 1시간 전 (본인 미응답 시)
 *
 * 서버 push (온라인 필요):
 *  - EVENT_CREATED      : 그룹원이 새 일정 추가
 *  - EVENT_UPDATED      : 본인 캘린더 일정의 시간/장소 변경 (default OFF)
 *  - POLL_CREATED         : 새 폴 발의
 *  - POLL_CONFIRMED       : 폴 확정
 *  - POLL_COMPLETED       : 내가 만든 폴이 확정/마감됨 (발의자에게만)
 *  - PHOTO_UPLOADED       : 그룹에 새 사진 (10분 묶음)
 *  - GROUP_JOINED_BY      : 새 멤버 가입 (그룹장에게만)
 */
public enum NotificationType {
    EVENT_REMINDER,
    EVENT_CREATED,
    EVENT_UPDATED,
    POLL_CREATED,
    POLL_DEADLINE_SOON,
    POLL_CONFIRMED,
    POLL_COMPLETED,
    PHOTO_UPLOADED,
    PHOTO_EXPIRING,
    GROUP_JOINED_BY,
}

package com.gatherit.api.notification.dto;

/**
 * 그룹 단위 prefs 부분 수정. 모든 필드 optional — null 이면 변경 X.
 */
public record GroupNotifPrefUpdateRequest(
        Boolean enabled,
        Boolean eventCreated,
        Boolean eventUpdated,
        Boolean pollCreated,
        Boolean pollDeadlineSoon,
        Boolean pollConfirmed,
        Boolean photoUploaded,
        Boolean photoExpiring,
        Boolean groupJoinedBy
) {}

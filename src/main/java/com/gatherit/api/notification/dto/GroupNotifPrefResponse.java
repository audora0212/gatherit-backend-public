package com.gatherit.api.notification.dto;

import com.gatherit.api.notification.entity.UserGroupNotifPref;

public record GroupNotifPrefResponse(
        boolean enabled,
        boolean eventCreated,
        boolean eventUpdated,
        boolean pollCreated,
        boolean pollDeadlineSoon,
        boolean pollConfirmed,
        boolean photoUploaded,
        boolean photoExpiring,
        boolean groupJoinedBy
) {
    public static GroupNotifPrefResponse from(UserGroupNotifPref p) {
        return new GroupNotifPrefResponse(
                p.isEnabled(),
                p.isEventCreated(), p.isEventUpdated(),
                p.isPollCreated(), p.isPollDeadlineSoon(), p.isPollConfirmed(),
                p.isPhotoUploaded(), p.isPhotoExpiring(), p.isGroupJoinedBy()
        );
    }
}

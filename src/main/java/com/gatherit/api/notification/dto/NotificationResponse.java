package com.gatherit.api.notification.dto;

import com.gatherit.api.notification.entity.Notification;
import com.gatherit.api.notification.entity.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        String title,
        String subtitle,
        String refKey,
        Instant readAt,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getType(), n.getTitle(), n.getSubtitle(),
                n.getRefKey(), n.getReadAt(), n.getCreatedAt()
        );
    }
}

package com.gatherit.api.announcement;

import java.time.Instant;

public record AnnouncementResponse(
        Long id,
        String title,
        String body,
        String type,            // NOTICE / UPDATE / MAINTENANCE
        Instant startsAt,
        Instant endsAt,
        Instant createdAt
) {
    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(
                a.getId(), a.getTitle(), a.getBody(), a.getType().name(),
                a.getStartsAt(), a.getEndsAt(), a.getCreatedAt());
    }
}

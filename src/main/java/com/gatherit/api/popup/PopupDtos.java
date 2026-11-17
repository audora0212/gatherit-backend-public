package com.gatherit.api.popup;

import java.time.Instant;

/** 팝업 DTO 모음 — 도메인이 작아 한 파일에. */
public final class PopupDtos {

    private PopupDtos() {}

    public record Response(
            Long id,
            String title,
            String body,
            String rule,                 // ONCE / DAILY
            Instant startsAt,
            Instant endsAt,
            String targetBelowVersion,
            boolean enabled,
            Instant createdAt
    ) {
        public static Response from(Popup p) {
            return new Response(p.getId(), p.getTitle(), p.getBody(), p.getRule().name(),
                    p.getStartsAt(), p.getEndsAt(), p.getTargetBelowVersion(), p.isEnabled(), p.getCreatedAt());
        }
    }
}

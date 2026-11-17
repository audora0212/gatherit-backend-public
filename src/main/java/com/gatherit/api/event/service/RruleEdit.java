package com.gatherit.api.event.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * RRULE 문자열 조작 유틸 — 반복 시리즈 분할(this-and-future)용.
 * 서버는 rrule 을 전개하지 않고 문자열만 다룬다(전개는 클라/외부 캘린더 담당).
 */
public final class RruleEdit {

    private RruleEdit() {}

    /** RFC5545 UNTIL 용 UTC 컴팩트 포맷 (yyyyMMddTHHmmssZ) — 값은 항상 UTC 이므로 'Z' 리터럴. */
    private static final DateTimeFormatter UNTIL_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * rrule 에 UNTIL 을 설정해 시리즈를 절단한다.
     * 기존 UNTIL/COUNT 토큰은 제거(둘은 상호배타 + 절단이 우선) 후 새 UNTIL 을 붙인다.
     * @param rrule 원본 RRULE (예: "FREQ=WEEKLY;BYDAY=MO;COUNT=10")
     * @param untilInclusive 마지막으로 포함할 Instant (보통 occurrenceStart - 1s)
     * @return UNTIL 이 세팅된 RRULE (예: "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z")
     */
    public static String withUntil(String rrule, Instant untilInclusive) {
        if (rrule == null || rrule.isBlank()) return rrule;
        List<String> kept = new ArrayList<>();
        for (String token : rrule.split(";")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            String key = t.contains("=") ? t.substring(0, t.indexOf('=')).trim().toUpperCase() : t.toUpperCase();
            if (key.equals("UNTIL") || key.equals("COUNT")) continue; // 절단으로 대체
            kept.add(t);
        }
        kept.add("UNTIL=" + UNTIL_FMT.format(untilInclusive));
        return String.join(";", kept);
    }
}

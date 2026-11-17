package com.gatherit.api.event.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** RruleEdit.withUntil — 시리즈 절단용 RRULE 문자열 조작 순수 단위테스트. */
class RruleEditTest {

    // 2026-06-28T23:59:59Z (occurrenceStart - 1s 형태)
    private static final Instant UNTIL = Instant.parse("2026-06-28T23:59:59Z");

    @Test
    @DisplayName("withUntil — FREQ/BYDAY 보존 + UNTIL 추가")
    void addsUntilPreservingRule() {
        String out = RruleEdit.withUntil("FREQ=WEEKLY;BYDAY=MO,WE", UNTIL);
        assertThat(out).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20260628T235959Z");
    }

    @Test
    @DisplayName("withUntil — 기존 COUNT 제거 후 UNTIL 로 대체")
    void stripsCount() {
        String out = RruleEdit.withUntil("FREQ=DAILY;COUNT=10", UNTIL);
        assertThat(out).isEqualTo("FREQ=DAILY;UNTIL=20260628T235959Z");
        assertThat(out).doesNotContain("COUNT");
    }

    @Test
    @DisplayName("withUntil — 기존 UNTIL 제거 후 새 UNTIL 로 대체(중복 없음)")
    void replacesExistingUntil() {
        String out = RruleEdit.withUntil("FREQ=WEEKLY;UNTIL=20271231T235959Z", UNTIL);
        assertThat(out).isEqualTo("FREQ=WEEKLY;UNTIL=20260628T235959Z");
        assertThat(out.split("UNTIL=")).hasSize(2); // UNTIL 한 번만
    }

    @Test
    @DisplayName("withUntil — null/빈 rrule 은 그대로")
    void nullPassthrough() {
        assertThat(RruleEdit.withUntil(null, UNTIL)).isNull();
        assertThat(RruleEdit.withUntil("", UNTIL)).isEmpty();
    }
}

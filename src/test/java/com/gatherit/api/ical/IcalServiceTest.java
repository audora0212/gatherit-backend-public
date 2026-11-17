package com.gatherit.api.ical;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IcalService 단위 테스트 — 외부 ical4j 없이 직접 만든 VCALENDAR 직렬화가 RFC 5545 규약을
 * 지키는지(시간 포맷/종일 exclusive DTEND/GEO 좌표 보존/TEXT escape/75-octet folding)와,
 * 멤버 아닌 그룹 요청을 걸러내는 보안 필터를 검증한다. repo 는 전부 mock.
 */
@ExtendWith(MockitoExtension.class)
class IcalServiceTest {

    private static final long USER_ID = 1L;
    private static final long MY_GROUP = 10L;
    private static final long CREATOR_ID = 7L;

    @Mock EventRepository eventRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock UserRepository userRepository;

    IcalService service;

    @BeforeEach
    void setUp() {
        service = new IcalService(eventRepository, groupRepository, groupMemberRepository, userRepository);
    }

    // ---- helpers ----

    private static Event event(long id, String title, Instant start, Instant end, boolean allDay) {
        Event e = Event.builder()
                .title(title).startAt(start).endAt(end)
                .allDay(allDay).createdBy(CREATOR_ID)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    /** buildUserCalendar 의 의존 경로(내 그룹 조회 → 그룹별 일정 → 닉네임 배치조회)를 stub 하고 렌더 결과를 반환. */
    private String renderUserCalendar(List<Event> events, List<Long> requestedGroups) {
        GroupMember gm = mock(GroupMember.class);
        when(gm.getGroupId()).thenReturn(MY_GROUP);
        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of(gm));
        when(eventRepository.findAllByGroupIds(anyList())).thenReturn(events);

        User creator = mock(User.class);
        when(creator.getId()).thenReturn(CREATOR_ID);
        when(creator.getNickname()).thenReturn("홍길동");
        when(userRepository.findAllById(anyList())).thenReturn(List.of(creator));

        return service.buildUserCalendar(USER_ID, requestedGroups);
    }

    @Test
    @DisplayName("VCALENDAR 골격 + 이벤트당 VEVENT 하나, CRLF 줄바꿈")
    void rendersValidSkeleton() {
        Event e = event(100, "회의", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics)
                .contains("BEGIN:VCALENDAR")
                .contains("VERSION:2.0")
                .contains("PRODID:-//gatherit//calendar//KO")
                .contains("BEGIN:VEVENT")
                .contains("UID:event-100@gatherit.co.kr")
                .contains("SUMMARY:회의")
                .contains("END:VEVENT")
                .endsWith("END:VCALENDAR\r\n");
        // VEVENT 정확히 1개
        assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(2);
        // 모든 라인은 CRLF 종결
        assertThat(ics).contains("\r\n");
    }

    @Test
    @DisplayName("시간 일정 → DTSTART/DTEND 를 UTC Z 로 표기")
    void timedEvent_usesUtcZulu() {
        Event e = event(1, "회의", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:30:00Z"), false);

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics)
                .contains("DTSTART:20260615T050000Z")
                .contains("DTEND:20260615T063000Z")
                .doesNotContain("VALUE=DATE");
    }

    @Test
    @DisplayName("종일 일정 → VALUE=DATE + DTEND 는 다음 날(exclusive)")
    void allDayEvent_usesValueDateExclusiveEnd() {
        // 2026-06-15 종일 (KST 00:00 ~ 23:59:59 = UTC 06-14T15:00 ~ 06-15T14:59:59)
        Event e = event(2, "휴가", Instant.parse("2026-06-14T15:00:00Z"),
                Instant.parse("2026-06-15T14:59:59Z"), true);

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics)
                .contains("DTSTART;VALUE=DATE:20260615")
                .contains("DTEND;VALUE=DATE:20260616"); // exclusive next-day
    }

    @Test
    @DisplayName("좌표 있으면 GEO:lat;lng, 없으면 GEO 없음 (round-trip 보존)")
    void geoLine_onlyWhenCoordsPresent() {
        Event withGeo = event(3, "맛집", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);
        ReflectionTestUtils.setField(withGeo, "latitude", 37.498);
        ReflectionTestUtils.setField(withGeo, "longitude", 127.027);
        Event noGeo = event(4, "통화", Instant.parse("2026-06-16T05:00:00Z"),
                Instant.parse("2026-06-16T06:00:00Z"), false);

        String ics = renderUserCalendar(List.of(withGeo, noGeo), null);

        assertThat(ics).contains("GEO:37.498;127.027");
        // 좌표 없는 이벤트 블록엔 GEO 가 없어야 함 → 전체에서 GEO 라인은 정확히 1개
        assertThat(ics.split("GEO:", -1)).hasSize(2);
    }

    @Test
    @DisplayName("LOCATION 은 placeName 우선(없으면 roadAddress → location)")
    void location_prefersPlaceName() {
        Event e = event(5, "모임", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);
        ReflectionTestUtils.setField(e, "placeName", "스타벅스 강남역점");
        ReflectionTestUtils.setField(e, "roadAddress", "서울 강남구 강남대로 1");
        ReflectionTestUtils.setField(e, "location", "강남");

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics).contains("LOCATION:스타벅스 강남역점");
    }

    @Test
    @DisplayName("RRULE 이 있으면 그대로 방출")
    void rrule_emittedWhenPresent() {
        Event e = event(6, "스터디", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);
        ReflectionTestUtils.setField(e, "rrule", "FREQ=WEEKLY;BYDAY=MO,WE,FR");

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics).contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR");
    }

    @Test
    @DisplayName("TEXT escape — 세미콜론/쉼표/백슬래시 (§3.3.11)")
    void escapesSpecialChars() {
        Event e = event(7, "A; B, C \\ D", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics).contains("SUMMARY:A\\; B\\, C \\\\ D");
    }

    @Test
    @DisplayName("멤버 아닌 그룹 요청은 걸러냄 (보안)")
    void filtersOutNonMemberGroups() {
        Event e = event(8, "회의", Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);

        // 요청: 내 그룹(10) + 남의 그룹(999) → 10 만 통과해야 함
        renderUserCalendar(List.of(e), List.of(MY_GROUP, 999L));

        verify(eventRepository).findAllByGroupIds(List.of(MY_GROUP));
    }

    @Test
    @DisplayName("75-octet line folding — 긴 줄은 CRLF+space 로 접힘")
    void foldsLongLines() {
        String longTitle = "가".repeat(60); // 한글 60자 = UTF-8 180바이트 → 반드시 fold
        Event e = event(9, longTitle, Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"), false);

        String ics = renderUserCalendar(List.of(e), null);

        assertThat(ics).contains("\r\n "); // continuation line 존재
        // 어떤 물리 라인도 75 octet 초과 금지
        for (String physicalLine : ics.split("\r\n")) {
            assertThat(physicalLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(75);
        }
    }

    @Nested
    @DisplayName("구독(token) 경로")
    class TokenPath {
        @Test
        @DisplayName("알 수 없는 토큰 → empty (404 유도, 정보 노출 X)")
        void unknownToken_returnsEmpty() {
            when(userRepository.findByIcalToken("bogus")).thenReturn(Optional.empty());

            Optional<String> out = service.buildUserCalendarByToken("bogus", null);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("null/blank 토큰 → empty (repo 조회조차 안 함)")
        void blankToken_returnsEmpty() {
            assertThat(service.buildUserCalendarByToken("  ", null)).isEmpty();
            assertThat(service.buildUserCalendarByToken(null, null)).isEmpty();
        }
    }
}

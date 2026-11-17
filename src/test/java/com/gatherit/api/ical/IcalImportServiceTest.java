package com.gatherit.api.ical;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.repository.GroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IcalImportService 단위 테스트.
 *
 * 검증 범위:
 *  - 입력 유효성(null/blank/초과 크기) → INVALID_INPUT
 *  - 개인그룹 없을 때 → GROUP_NOT_FOUND
 *  - 정상 VEVENT 파싱 후 save 호출 확인
 *  - 중복 UID(import_uid) → skipped 카운트
 *  - DTSTART 없는 항목 → skipped 카운트
 *  - 종일 이벤트(VALUE=DATE) 파싱
 *  - TZID 파라미터 있는 시간대 파싱
 *  - UTC(Z 접미사) 파싱
 *  - GEO 좌표 파싱
 *  - RRULE 보존
 *  - TEXT unescape (\\n, \\;, \\,)
 *  - 총량 cap(MAX_IMPORT=300) → truncated=true
 *  - 사용자 총량 cap(USER_EVENT_CAP=2000) → truncated=true
 *  - UID 없는 항목 합성 UID 로 중복 방지
 *  - CRLF 접힘(line unfolding) 처리
 *  - VTIMEZONE 블록 등 비 VEVENT 블록 무시
 *  - title/location/memo 길이 잘림(trunc)
 *  - endAt < startAt → endAt = startAt 보정
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IcalImportServiceTest {

    private static final long USER_ID = 1L;
    private static final long PERSONAL_GROUP_ID = 10L;

    @Mock EventRepository eventRepository;
    @Mock EventGroupRepository eventGroupRepository;
    @Mock GroupRepository groupRepository;

    IcalImportService service;

    // ---- 헬퍼 ----

    /** 개인 그룹 stub 생성 */
    private Group personalGroup() {
        Group g = Group.builder()
                .name("내 캘린더")
                .colorKey(GroupColor.pink)
                .createdBy(USER_ID)
                .personal(true)
                .build();
        ReflectionTestUtils.setField(g, "id", PERSONAL_GROUP_ID);
        return g;
    }

    /** 개인 그룹 stub + countByCreatedBy(0) 의 공통 세팅 */
    private void stubPersonalGroupAndNoExistingEvents() {
        when(groupRepository.findAllByMemberUserId(USER_ID)).thenReturn(List.of(personalGroup()));
        when(eventRepository.countByCreatedBy(USER_ID)).thenReturn(0L);
    }

    /** save(Event) 시 id를 1L로 주입해 EventGroup 저장이 NPE 없이 통과하게 한다 */
    private void stubEventSaveWithId() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    /** 최소한의 유효 .ics 문자열 */
    private static String singleEvent(String uid, String dtstart, String dtend, String summary) {
        return "BEGIN:VCALENDAR\r\n" +
               "VERSION:2.0\r\n" +
               "BEGIN:VEVENT\r\n" +
               "UID:" + uid + "\r\n" +
               "DTSTART:" + dtstart + "\r\n" +
               "DTEND:" + dtend + "\r\n" +
               "SUMMARY:" + summary + "\r\n" +
               "END:VEVENT\r\n" +
               "END:VCALENDAR\r\n";
    }

    @BeforeEach
    void setUp() {
        service = new IcalImportService(eventRepository, eventGroupRepository, groupRepository);
        // 기본 stub: existsByCreatedByAndImportUid → false (중복 없음)
        when(eventRepository.existsByCreatedByAndImportUid(anyLong(), anyString())).thenReturn(false);
    }

    // ────────────────────────────────────────────────────────────────
    // 입력 유효성
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("입력 유효성 검사")
    class InputValidation {

        @Test
        @DisplayName("null ics → INVALID_INPUT")
        void nullIcs_throwsInvalidInput() {
            assertThatThrownBy(() -> service.importIcs(USER_ID, null))
                    .isInstanceOf(CustomException.class)
                    .extracting(IcalImportServiceTest::codeOf)
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("blank ics → INVALID_INPUT")
        void blankIcs_throwsInvalidInput() {
            assertThatThrownBy(() -> service.importIcs(USER_ID, "   "))
                    .isInstanceOf(CustomException.class)
                    .extracting(IcalImportServiceTest::codeOf)
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("2_000_001자 초과 → INVALID_INPUT")
        void oversizedIcs_throwsInvalidInput() {
            String giant = "x".repeat(2_000_001);
            assertThatThrownBy(() -> service.importIcs(USER_ID, giant))
                    .isInstanceOf(CustomException.class)
                    .extracting(IcalImportServiceTest::codeOf)
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("개인 그룹 없으면 → GROUP_NOT_FOUND")
        void noPersonalGroup_throwsGroupNotFound() {
            // personal=false 인 그룹만 반환
            Group nonPersonal = Group.builder()
                    .name("팀 그룹")
                    .colorKey(GroupColor.team)
                    .createdBy(USER_ID)
                    .personal(false)
                    .build();
            ReflectionTestUtils.setField(nonPersonal, "id", 99L);
            when(groupRepository.findAllByMemberUserId(USER_ID)).thenReturn(List.of(nonPersonal));

            String ics = singleEvent("uid1@test", "20260615T090000Z", "20260615T100000Z", "회의");

            assertThatThrownBy(() -> service.importIcs(USER_ID, ics))
                    .isInstanceOf(CustomException.class)
                    .extracting(IcalImportServiceTest::codeOf)
                    .isEqualTo(ErrorCode.GROUP_NOT_FOUND);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 정상 파싱 / save 호출
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("정상 VEVENT 파싱")
    class NormalParsing {

        @Test
        @DisplayName("단일 VEVENT → imported=1, Event/EventGroup save 각 1회")
        void singleValidEvent_importedOne() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = singleEvent("abc-123@gatherit", "20260615T090000Z", "20260615T100000Z", "팀 회의");
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.imported()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(0);
            assertThat(result.truncated()).isFalse();
            verify(eventRepository, times(1)).save(any(Event.class));
            verify(eventGroupRepository, times(1)).save(any(EventGroup.class));
        }

        @Test
        @DisplayName("저장된 Event 의 title, importUid, allDay, createdBy 검증")
        void savedEvent_fieldsAreCorrect() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = singleEvent("uid-field-check@test",
                    "20260620T100000Z", "20260620T110000Z", "필드확인");
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event saved = captor.getValue();

            assertThat(saved.getTitle()).isEqualTo("필드확인");
            assertThat(saved.getImportUid()).isEqualTo("uid-field-check@test");
            assertThat(saved.isAllDay()).isFalse();
            assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("저장된 EventGroup 의 groupId = 개인 그룹 ID")
        void savedEventGroup_hasPersonalGroupId() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = singleEvent("eg-check@test", "20260620T100000Z", "20260620T110000Z", "그룹확인");
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<EventGroup> egCaptor = ArgumentCaptor.forClass(EventGroup.class);
            verify(eventGroupRepository).save(egCaptor.capture());
            assertThat(egCaptor.getValue().getGroupId()).isEqualTo(PERSONAL_GROUP_ID);
        }

        @Test
        @DisplayName("summary 없는 VEVENT → title = '(제목 없음)'")
        void noSummary_defaultTitle() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:no-title@test\r\n" +
                         "DTSTART:20260615T090000Z\r\n" +
                         "DTEND:20260615T100000Z\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("(제목 없음)");
        }

        @Test
        @DisplayName("LOCATION, DESCRIPTION, RRULE 필드 보존")
        void locationDescriptionRrule_saved() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:full-fields@test\r\n" +
                         "DTSTART:20260615T090000Z\r\n" +
                         "DTEND:20260615T100000Z\r\n" +
                         "SUMMARY:상세 일정\r\n" +
                         "LOCATION:서울특별시 강남구\r\n" +
                         "DESCRIPTION:설명 텍스트\r\n" +
                         "RRULE:FREQ=WEEKLY;BYDAY=MO\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event e = captor.getValue();
            assertThat(e.getLocation()).isEqualTo("서울특별시 강남구");
            assertThat(e.getMemo()).isEqualTo("설명 텍스트");
            assertThat(e.getRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 중복 skip
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("중복(importUid) skip")
    class DuplicateSkip {

        @Test
        @DisplayName("이미 존재하는 UID → skipped=1, save 없음")
        void existingUid_skipped() {
            stubPersonalGroupAndNoExistingEvents();
            when(eventRepository.existsByCreatedByAndImportUid(eq(USER_ID), eq("dup-uid@test")))
                    .thenReturn(true);

            String ics = singleEvent("dup-uid@test", "20260615T090000Z", "20260615T100000Z", "중복");
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.imported()).isEqualTo(0);
            assertThat(result.skipped()).isEqualTo(1);
            verify(eventRepository, never()).save(any(Event.class));
        }

        @Test
        @DisplayName("UID 없는 항목 — 합성 UID 로 중복 방지, 첫 번째는 import, 동일 내용 두 번째는 skip")
        void noUid_syntheticUidDeduplication() {
            stubPersonalGroupAndNoExistingEvents();

            String dtstart = "20260615T090000Z";
            String dtend   = "20260615T100000Z";
            String summary = "NoUidEvent";

            // 합성 UID를 미리 계산: "syn-" + hex of (startAt|summary|endAt).hashCode()
            // 실제 합성 UID는 서비스 내부에서 결정되므로, 두 번째 import 에서 existsByCreatedByAndImportUid=true
            // 로 반환하도록 anyString() 으로 광범위하게 stub
            when(eventRepository.existsByCreatedByAndImportUid(anyLong(), anyString()))
                    .thenReturn(false)   // 첫 번째 호출 → import
                    .thenReturn(true);   // 두 번째 호출 → skip

            // 동일 VEVENT 두 개를 한 ics 에 넣음
            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "DTSTART:" + dtstart + "\r\n" +
                         "DTEND:" + dtend + "\r\n" +
                         "SUMMARY:" + summary + "\r\n" +
                         "END:VEVENT\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "DTSTART:" + dtstart + "\r\n" +
                         "DTEND:" + dtend + "\r\n" +
                         "SUMMARY:" + summary + "\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";

            stubEventSaveWithId();
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.imported()).isEqualTo(1);
            assertThat(result.skipped()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // DTSTART 없는 항목
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DTSTART 없는 VEVENT → skipped, save 없음")
    void missingDtstart_skipped() {
        stubPersonalGroupAndNoExistingEvents();

        String ics = "BEGIN:VCALENDAR\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:no-dtstart@test\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:시작없음\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
        verify(eventRepository, never()).save(any(Event.class));
    }

    // ────────────────────────────────────────────────────────────────
    // 날짜/시간대 파싱
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("날짜/시간대 파싱")
    class DateTimeParsing {

        @Test
        @DisplayName("UTC(Z) 포맷 → 정확한 startAt Instant")
        void utcZFormat_parsedCorrectly() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = singleEvent("utc-test@test", "20260615T090000Z", "20260615T100000Z", "UTC일정");
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().getStartAt())
                    .isEqualTo(Instant.parse("2026-06-15T09:00:00Z"));
        }

        @Test
        @DisplayName("VALUE=DATE(종일) DTSTART/DTEND → allDay=true, startAt=KST 00:00, endAt=종료일 전날 23:59:59 KST")
        void allDayValueDate_parsedAsAllDay() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:allday@test\r\n" +
                         "DTSTART;VALUE=DATE:20260620\r\n" +
                         "DTEND;VALUE=DATE:20260621\r\n" +  // exclusive next day
                         "SUMMARY:종일 일정\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event e = captor.getValue();
            assertThat(e.isAllDay()).isTrue();
            // 2026-06-20 KST 00:00:00 = UTC 2026-06-19T15:00:00Z
            assertThat(e.getStartAt()).isEqualTo(Instant.parse("2026-06-19T15:00:00Z"));
            // DTEND=20260621(exclusive) → 20260620 23:59:59 KST = UTC 2026-06-20T14:59:59Z
            assertThat(e.getEndAt()).isEqualTo(Instant.parse("2026-06-20T14:59:59Z"));
        }

        @Test
        @DisplayName("8자리 순수 날짜(VALUE=DATE 없이도) → allDay=true 처리")
        void eightDigitDate_inferredAsAllDay() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:date-only@test\r\n" +
                         "DTSTART:20260620\r\n" +
                         "DTEND:20260621\r\n" +
                         "SUMMARY:날짜만\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().isAllDay()).isTrue();
        }

        @Test
        @DisplayName("TZID=Asia/Seoul 파라미터 → KST 시간대 적용 후 올바른 Instant")
        void tzidSeoul_parsedWithKst() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:kst-tzid@test\r\n" +
                         "DTSTART;TZID=Asia/Seoul:20260615T180000\r\n" +
                         "DTEND;TZID=Asia/Seoul:20260615T190000\r\n" +
                         "SUMMARY:KST 일정\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            // 2026-06-15T18:00:00 KST = 2026-06-15T09:00:00Z
            assertThat(captor.getValue().getStartAt())
                    .isEqualTo(Instant.parse("2026-06-15T09:00:00Z"));
        }

        @Test
        @DisplayName("TZID=America/New_York → 올바른 UTC 변환")
        void tzidNewYork_parsedCorrectly() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:ny-tzid@test\r\n" +
                         "DTSTART;TZID=America/New_York:20260615T090000\r\n" +
                         "DTEND;TZID=America/New_York:20260615T100000\r\n" +
                         "SUMMARY:뉴욕 일정\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            // 2026-06-15T09:00 EDT (UTC-4) = 2026-06-15T13:00:00Z
            assertThat(captor.getValue().getStartAt())
                    .isEqualTo(Instant.parse("2026-06-15T13:00:00Z"));
        }

        @Test
        @DisplayName("floating 시간(TZID 없음, Z 없음) → KST로 해석")
        void floatingTime_treatedAsKst() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:floating@test\r\n" +
                         "DTSTART:20260615T090000\r\n" +
                         "DTEND:20260615T100000\r\n" +
                         "SUMMARY:Floating\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            // 2026-06-15T09:00 KST = 2026-06-15T00:00:00Z
            assertThat(captor.getValue().getStartAt())
                    .isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
        }

        @Test
        @DisplayName("endAt < startAt → endAt = startAt 으로 보정")
        void endBeforeStart_correctedToStart() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            // endAt이 startAt보다 이른 케이스 (서비스 내 보정 로직)
            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:end-before-start@test\r\n" +
                         "DTSTART:20260615T100000Z\r\n" +
                         "DTEND:20260615T090000Z\r\n" +  // end < start
                         "SUMMARY:역순\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event e = captor.getValue();
            // endAt은 startAt 이상이어야 함
            assertThat(e.getEndAt()).isAfterOrEqualTo(e.getStartAt());
        }

        @Test
        @DisplayName("DTEND 없으면 — 일반 일정은 startAt + 1시간으로 채움")
        void noEndAt_timedEvent_defaultsToOneHour() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:no-end@test\r\n" +
                         "DTSTART:20260615T090000Z\r\n" +
                         "SUMMARY:끝없는일정\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event e = captor.getValue();
            Instant expectedEnd = Instant.parse("2026-06-15T10:00:00Z");
            assertThat(e.getEndAt()).isEqualTo(expectedEnd);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // GEO 좌표
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GEO 좌표 파싱")
    class GeoParsing {

        @Test
        @DisplayName("GEO:lat;lng → latitude/longitude 보존")
        void geoLine_parsedIntoCoordinates() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:geo@test\r\n" +
                         "DTSTART:20260615T090000Z\r\n" +
                         "DTEND:20260615T100000Z\r\n" +
                         "SUMMARY:좌표 있음\r\n" +
                         "GEO:37.498;127.027\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            Event e = captor.getValue();
            assertThat(e.getLatitude()).isEqualTo(37.498);
            assertThat(e.getLongitude()).isEqualTo(127.027);
        }

        @Test
        @DisplayName("GEO 없으면 latitude/longitude = null")
        void noGeo_coordinatesNull() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = singleEvent("no-geo@test", "20260615T090000Z", "20260615T100000Z", "좌표없음");
            service.importIcs(USER_ID, ics);

            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(captor.capture());
            assertThat(captor.getValue().getLatitude()).isNull();
            assertThat(captor.getValue().getLongitude()).isNull();
        }

        @Test
        @DisplayName("잘못된 GEO(숫자 아닌 값) → 무시하고 import 성공")
        void invalidGeo_ignoredAndImportSucceeds() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = "BEGIN:VCALENDAR\r\n" +
                         "BEGIN:VEVENT\r\n" +
                         "UID:bad-geo@test\r\n" +
                         "DTSTART:20260615T090000Z\r\n" +
                         "DTEND:20260615T100000Z\r\n" +
                         "SUMMARY:잘못된 좌표\r\n" +
                         "GEO:not;a;number\r\n" +
                         "END:VEVENT\r\n" +
                         "END:VCALENDAR\r\n";
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.imported()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // TEXT unescape
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TEXT unescape — \\n→줄바꿈, \\;→;, \\,→, 처리")
    void textUnescape_specialChars() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        String ics = "BEGIN:VCALENDAR\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:unescape@test\r\n" +
                     "DTSTART:20260615T090000Z\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:A\\;B\\,C\r\n" +
                     "DESCRIPTION:첫줄\\n둘째줄\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        service.importIcs(USER_ID, ics);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        Event e = captor.getValue();
        assertThat(e.getTitle()).isEqualTo("A;B,C");
        assertThat(e.getMemo()).contains("\n");
    }

    // ────────────────────────────────────────────────────────────────
    // line unfolding
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CRLF+space line folding → unfold 후 정상 파싱")
    void lineFolding_unfolded() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        // SUMMARY 를 두 줄로 접은 형식
        String ics = "BEGIN:VCALENDAR\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:folded@test\r\n" +
                     "DTSTART:20260615T090000Z\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:긴제목이라서두\r\n" +
                     " 줄에걸쳐있음\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        service.importIcs(USER_ID, ics);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        // unfold 후 이어진 문자열이 title에 포함되어야 함
        assertThat(captor.getValue().getTitle()).contains("긴제목이라서두줄에걸쳐있음");
    }

    // ────────────────────────────────────────────────────────────────
    // 총량 cap
    // ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("총량 cap(truncated)")
    class TruncationCap {

        /** n개의 VEVENT 블록을 담은 .ics 문자열 생성 */
        private String buildIcsWithEvents(int count) {
            StringBuilder sb = new StringBuilder("BEGIN:VCALENDAR\r\n");
            for (int i = 0; i < count; i++) {
                sb.append("BEGIN:VEVENT\r\n");
                sb.append("UID:cap-uid-").append(i).append("@test\r\n");
                sb.append("DTSTART:20260615T090000Z\r\n");
                sb.append("DTEND:20260615T100000Z\r\n");
                sb.append("SUMMARY:일정").append(i).append("\r\n");
                sb.append("END:VEVENT\r\n");
            }
            sb.append("END:VCALENDAR\r\n");
            return sb.toString();
        }

        @Test
        @DisplayName("301개 VEVENT → truncated=true, imported ≤ 300")
        void over300Events_truncated() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = buildIcsWithEvents(301);
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.truncated()).isTrue();
            assertThat(result.imported()).isLessThanOrEqualTo(300);
        }

        @Test
        @DisplayName("300개 이하 → truncated=false")
        void under300Events_notTruncated() {
            stubPersonalGroupAndNoExistingEvents();
            stubEventSaveWithId();

            String ics = buildIcsWithEvents(5);
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.truncated()).isFalse();
            assertThat(result.imported()).isEqualTo(5);
        }

        @Test
        @DisplayName("사용자 총량 cap(USER_EVENT_CAP=2000) 도달 → truncated=true")
        void userEventCapReached_truncated() {
            when(groupRepository.findAllByMemberUserId(USER_ID)).thenReturn(List.of(personalGroup()));
            when(eventRepository.countByCreatedBy(USER_ID)).thenReturn(2000L);  // 이미 상한 도달

            String ics = buildIcsWithEvents(10);
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.truncated()).isTrue();
            assertThat(result.imported()).isEqualTo(0);
            verify(eventRepository, never()).save(any(Event.class));
        }

        @Test
        @DisplayName("남은 캡 내에서만 import (capRemaining=2 → 10개 중 2개만 import, truncated=true)")
        void partialCapRemaining_onlyImportsUpToCap() {
            when(groupRepository.findAllByMemberUserId(USER_ID)).thenReturn(List.of(personalGroup()));
            when(eventRepository.countByCreatedBy(USER_ID)).thenReturn(1998L);  // 2개 여유
            stubEventSaveWithId();

            String ics = buildIcsWithEvents(10);
            IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

            assertThat(result.truncated()).isTrue();
            assertThat(result.imported()).isEqualTo(2);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 필드 길이 잘림(trunc)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("title 100자 초과 → 100자로 잘림")
    void titleTruncatedAt100Chars() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        String longTitle = "가".repeat(150);
        String ics = "BEGIN:VCALENDAR\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:long-title@test\r\n" +
                     "DTSTART:20260615T090000Z\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:" + longTitle + "\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        service.importIcs(USER_ID, ics);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(100);
    }

    @Test
    @DisplayName("importUid 255자 초과 → 255자로 잘림")
    void importUidTruncatedAt255Chars() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        String longUid = "u".repeat(300) + "@test";
        String ics = singleEvent(longUid, "20260615T090000Z", "20260615T100000Z", "긴UID");
        service.importIcs(USER_ID, ics);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getImportUid()).hasSizeLessThanOrEqualTo(255);
    }

    // ────────────────────────────────────────────────────────────────
    // VTIMEZONE/VALARM 등 비 VEVENT 블록 무시
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("VTIMEZONE 블록 포함 .ics → VTIMEZONE 무시하고 VEVENT만 파싱")
    void vtimezoneBlock_ignored() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        String ics = "BEGIN:VCALENDAR\r\n" +
                     "VERSION:2.0\r\n" +
                     "BEGIN:VTIMEZONE\r\n" +
                     "TZID:Asia/Seoul\r\n" +
                     "BEGIN:STANDARD\r\n" +
                     "DTSTART:19700101T000000\r\n" +
                     "TZOFFSETFROM:+0900\r\n" +
                     "TZOFFSETTO:+0900\r\n" +
                     "END:STANDARD\r\n" +
                     "END:VTIMEZONE\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:tz-ignored@test\r\n" +
                     "DTSTART;TZID=Asia/Seoul:20260615T180000\r\n" +
                     "DTEND;TZID=Asia/Seoul:20260615T190000\r\n" +
                     "SUMMARY:VTIMEZONE 함께\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

        assertThat(result.imported()).isEqualTo(1);
        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("VALARM 블록 포함 → VALARM 무시하고 VEVENT 정상 파싱")
    void valarmBlock_ignored() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        String ics = "BEGIN:VCALENDAR\r\n" +
                     "BEGIN:VEVENT\r\n" +
                     "UID:valarm@test\r\n" +
                     "DTSTART:20260615T090000Z\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:알람포함\r\n" +
                     "BEGIN:VALARM\r\n" +
                     "TRIGGER:-PT15M\r\n" +
                     "ACTION:DISPLAY\r\n" +
                     "END:VALARM\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";
        IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

        assertThat(result.imported()).isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────
    // 복합 시나리오
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mixed .ics — 정상1 + 중복1 + DTSTART없음1 → imported=1, skipped=2")
    void mixedIcs_correctCountsReturned() {
        stubPersonalGroupAndNoExistingEvents();
        stubEventSaveWithId();

        when(eventRepository.existsByCreatedByAndImportUid(eq(USER_ID), eq("dup@test")))
                .thenReturn(true);

        String ics = "BEGIN:VCALENDAR\r\n" +
                     // 정상
                     "BEGIN:VEVENT\r\n" +
                     "UID:ok@test\r\n" +
                     "DTSTART:20260615T090000Z\r\n" +
                     "DTEND:20260615T100000Z\r\n" +
                     "SUMMARY:정상\r\n" +
                     "END:VEVENT\r\n" +
                     // 중복
                     "BEGIN:VEVENT\r\n" +
                     "UID:dup@test\r\n" +
                     "DTSTART:20260616T090000Z\r\n" +
                     "DTEND:20260616T100000Z\r\n" +
                     "SUMMARY:중복\r\n" +
                     "END:VEVENT\r\n" +
                     // DTSTART 없음
                     "BEGIN:VEVENT\r\n" +
                     "UID:nostart@test\r\n" +
                     "DTEND:20260617T100000Z\r\n" +
                     "SUMMARY:시작없음\r\n" +
                     "END:VEVENT\r\n" +
                     "END:VCALENDAR\r\n";

        IcalImportService.ImportResult result = service.importIcs(USER_ID, ics);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }
}

package com.gatherit.api.event.service;

import com.gatherit.api.event.dto.EditScope;
import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventUpdateRequest;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.event.repository.DdayPinRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupMemberPolicy;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EventService 단위 테스트 — 권한/검증/가시성 규칙에 집중:
 *  - create 입력 검증(시간 역전 / 빈 그룹) + 멤버십/생성권한 정책
 *  - 가시성 모델: 일정에 연결된 그룹 중 하나라도 내가 멤버여야 조회 가능(createdBy 아님)
 *  - 소유권: 수정/삭제는 createdBy 본인만
 *  - 폴 출처 일정의 groupIds 잠금
 * (정상 경로의 응답 빌드/색 계산은 repository 슬라이스 테스트에서 실 DB 로 검증)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceTest {

    private static final long USER = 1L;
    private static final long OWNER = 7L;
    private static final long OTHER = 8L;
    private static final long EVENT_ID = 50L;
    private static final long GROUP_ID = 10L;

    @Mock EventRepository eventRepository;
    @Mock DdayPinRepository ddayPinRepository;
    @Mock EventGroupRepository eventGroupRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository groupRepository;
    @Mock PollRepository pollRepository;
    @Mock NotificationService notificationService;
    @Mock UserRepository userRepository;

    EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, ddayPinRepository, eventGroupRepository, groupMemberRepository,
                groupRepository, pollRepository, notificationService, userRepository);
    }

    // ---- helpers ----

    private EventCreateRequest createReq(Instant start, Instant end, List<Long> groupIds) {
        return new EventCreateRequest("회의", start, end, null, null, null, groupIds,
                null, null, null, null, null, null, null, null, null, null);
    }

    private EventUpdateRequest updateGroupsReq(List<Long> groupIds) {
        return new EventUpdateRequest(null, null, null, null, null, null, groupIds,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 반복 회차 수정/삭제 요청 — title 만 바꾸는 최소 형태. */
    private EventUpdateRequest occurrenceReq(EditScope scope, Instant occurrenceStart, String title) {
        return new EventUpdateRequest(title, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, scope, occurrenceStart);
    }

    private Event event(long id, long createdBy, Long fromPollId) {
        Event e = Event.builder()
                .title("회의")
                .startAt(Instant.parse("2026-06-15T05:00:00Z"))
                .endAt(Instant.parse("2026-06-15T06:00:00Z"))
                .createdBy(createdBy)
                .fromPollId(fromPollId)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    /** rrule 있는 반복 시리즈 (startAt = 2026-06-15T05:00:00Z). */
    private Event recurringEvent(long id, long createdBy, String rrule) {
        Event e = Event.builder()
                .title("주간 회의")
                .startAt(Instant.parse("2026-06-15T05:00:00Z"))
                .endAt(Instant.parse("2026-06-15T06:00:00Z"))
                .createdBy(createdBy)
                .rrule(rrule)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ---- create: 입력 검증 ----

    @Test
    @DisplayName("create — 종료시간이 시작보다 빠르면 EVENT_TIME_INVALID")
    void create_endBeforeStart() {
        EventCreateRequest req = createReq(
                Instant.parse("2026-06-15T06:00:00Z"),
                Instant.parse("2026-06-15T05:00:00Z"),
                List.of(GROUP_ID));

        assertThatThrownBy(() -> service.create(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_TIME_INVALID);
    }

    @Test
    @DisplayName("create — 그룹이 비면 EVENT_NO_GROUPS")
    void create_emptyGroups() {
        EventCreateRequest req = createReq(
                Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"),
                List.of());

        assertThatThrownBy(() -> service.create(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NO_GROUPS);
    }

    @Test
    @DisplayName("create — 멤버 아닌 그룹 지정 시 NOT_GROUP_MEMBER")
    void create_nonMemberGroup() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, USER)).thenReturn(false);
        EventCreateRequest req = createReq(
                Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"),
                List.of(GROUP_ID));

        assertThatThrownBy(() -> service.create(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("create — OWNER_ONLY 그룹에 비방장이 생성 시 GROUP_EVENT_CREATE_FORBIDDEN")
    void create_ownerOnlyPolicy_nonOwner() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, USER)).thenReturn(true);
        Group group = mock(Group.class);
        when(group.getEventCreatePolicy()).thenReturn(GroupMemberPolicy.OWNER_ONLY);
        when(group.isOwner(USER)).thenReturn(false);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        EventCreateRequest req = createReq(
                Instant.parse("2026-06-15T05:00:00Z"),
                Instant.parse("2026-06-15T06:00:00Z"),
                List.of(GROUP_ID));

        assertThatThrownBy(() -> service.create(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.GROUP_EVENT_CREATE_FORBIDDEN);
    }

    // ---- 가시성 모델 ----

    @Test
    @DisplayName("getDetail — 일정의 어떤 그룹에도 내가 멤버가 아니면 EVENT_FORBIDDEN (createdBy 무관)")
    void getDetail_notMemberOfAnyGroup_forbidden() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, OWNER, null)));
        EventGroup eg = mock(EventGroup.class);
        when(eg.getGroupId()).thenReturn(GROUP_ID);
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.getDetail(OTHER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_FORBIDDEN);
    }

    // ---- 소유권 ----

    @Test
    @DisplayName("update — 작성자가 아니면 EVENT_FORBIDDEN")
    void update_nonOwner_forbidden() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, OWNER, null)));

        assertThatThrownBy(() -> service.update(OTHER, EVENT_ID, updateGroupsReq(List.of(GROUP_ID))))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_FORBIDDEN);
    }

    @Test
    @DisplayName("delete — 작성자가 아니면 EVENT_FORBIDDEN")
    void delete_nonOwner_forbidden() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, OWNER, null)));

        assertThatThrownBy(() -> service.delete(OTHER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_FORBIDDEN);
    }

    // ---- 폴 출처 일정 그룹 잠금 ----

    @Test
    @DisplayName("update — 폴 출처 일정의 groupIds 변경 시 EVENT_GROUPS_LOCKED")
    void update_fromPollEvent_groupsLocked() {
        Event pollEvent = event(EVENT_ID, OWNER, 5L); // fromPollId set
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(pollEvent));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of()); // previousGroupIds

        assertThatThrownBy(() -> service.update(OWNER, EVENT_ID, updateGroupsReq(List.of(GROUP_ID, 11L))))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_GROUPS_LOCKED);
    }

    // ---- 반복 회차별 수정/삭제 (v1.5.2 EXDATE) ----

    @Test
    @DisplayName("update THIS — 반복 일정이 아니면 INVALID_INPUT")
    void updateThis_nonRecurring_invalid() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, OWNER, null)));
        EventUpdateRequest req = occurrenceReq(EditScope.THIS, Instant.parse("2026-06-22T05:00:00Z"), "x");

        assertThatThrownBy(() -> service.update(OWNER, EVENT_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("update THIS_AND_FUTURE — occurrenceStart 없으면 INVALID_INPUT")
    void updateFuture_noOccurrence_invalid() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY")));
        EventUpdateRequest req = occurrenceReq(EditScope.THIS_AND_FUTURE, null, "x");

        assertThatThrownBy(() -> service.update(OWNER, EVENT_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("delete THIS — 시리즈에 EXDATE 추가, 이벤트 삭제 안 함")
    void deleteThis_addsExdate() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());
        when(eventRepository.findByRecurrenceParentId(EVENT_ID)).thenReturn(List.of());
        Instant occ = Instant.parse("2026-06-22T05:00:00Z");

        service.delete(OWNER, EVENT_ID, EditScope.THIS, occ);

        assertThat(series.getExdatesList()).containsExactly(occ);
        verify(eventRepository, never()).delete(any());
    }

    @Test
    @DisplayName("delete THIS_AND_FUTURE(중간 회차) — rrule 에 UNTIL 절단, 시리즈 유지")
    void deleteFuture_middle_truncates() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());
        when(eventRepository.findByRecurrenceParentId(EVENT_ID)).thenReturn(List.of());
        Instant occ = Instant.parse("2026-06-29T05:00:00Z"); // series start(6/15) 이후

        service.delete(OWNER, EVENT_ID, EditScope.THIS_AND_FUTURE, occ);

        assertThat(series.getRrule()).contains("UNTIL=");
        verify(eventRepository, never()).delete(any());
    }

    @Test
    @DisplayName("delete THIS_AND_FUTURE(첫 회차) — 시리즈 통째 삭제로 위임")
    void deleteFuture_first_deletesSeries() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());
        when(eventRepository.findByRecurrenceParentId(EVENT_ID)).thenReturn(List.of());
        Instant occ = Instant.parse("2026-06-15T05:00:00Z"); // == series start

        service.delete(OWNER, EVENT_ID, EditScope.THIS_AND_FUTURE, occ);

        verify(eventRepository).delete(series);
    }

    @Test
    @DisplayName("delete THIS — 오래된 그림자 RRULE을 복구하고 EXDATE를 전파")
    void deleteThis_repairsAndSyncsRecurringShadow() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        Event shadow = recurringEvent(EVENT_ID + 1, OWNER, null);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of(shadow));
        Instant occ = Instant.parse("2026-06-22T05:00:00Z");

        service.delete(OWNER, EVENT_ID, EditScope.THIS, occ);

        assertThat(shadow.getRrule()).isEqualTo("FREQ=WEEKLY");
        assertThat(shadow.getExdatesList()).containsExactly(occ);
    }

    @Test
    @DisplayName("delete THIS_AND_FUTURE — 절단된 RRULE과 EXDATE를 그림자에 전파")
    void deleteFuture_syncsRecurringShadow() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        Event shadow = recurringEvent(EVENT_ID + 1, OWNER, "FREQ=WEEKLY");
        Instant oldExcluded = Instant.parse("2026-06-22T05:00:00Z");
        Instant futureExcluded = Instant.parse("2026-07-06T05:00:00Z");
        series.addExdate(oldExcluded);
        series.addExdate(futureExcluded);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of(shadow));

        service.delete(OWNER, EVENT_ID, EditScope.THIS_AND_FUTURE,
                Instant.parse("2026-06-29T05:00:00Z"));

        assertThat(shadow.getRrule()).isEqualTo(series.getRrule()).contains("UNTIL=");
        assertThat(shadow.getExdatesList()).containsExactly(oldExcluded);
    }

    @Test
    @DisplayName("update THIS/FUTURE — 공유 그림자가 있으면 원본 변경 전에 거부")
    void updateOccurrence_withShadow_rejectedBeforeMutation() {
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        when(eventRepository.findByShadowOfEventId(EVENT_ID))
                .thenReturn(List.of(recurringEvent(EVENT_ID + 1, OWNER, null)));
        Instant occ = Instant.parse("2026-06-22T05:00:00Z");

        assertThatThrownBy(() -> service.update(OWNER, EVENT_ID,
                occurrenceReq(EditScope.THIS, occ, "새 제목")))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.update(OWNER, EVENT_ID,
                occurrenceReq(EditScope.THIS_AND_FUTURE, occ, "새 제목")))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(series.getRrule()).isEqualTo("FREQ=WEEKLY");
        assertThat(series.getExdatesList()).isEmpty();
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("update THIS — EXDATE 추가 + 오버라이드(recurrence 링크) 생성")
    void updateThis_createsOverride() {
        Instant occ = Instant.parse("2026-06-22T05:00:00Z");
        Event series = recurringEvent(EVENT_ID, OWNER, "FREQ=WEEKLY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(series));
        EventGroup eg = mock(EventGroup.class);
        when(eg.getGroupId()).thenReturn(GROUP_ID);
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());
        when(eventRepository.findByRecurrenceParentIdAndRecurrenceId(EVENT_ID, occ)).thenReturn(List.of());

        service.update(OWNER, EVENT_ID, occurrenceReq(EditScope.THIS, occ, "새 제목"));

        assertThat(series.getExdatesList()).containsExactly(occ);
        ArgumentCaptor<Event> cap = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(cap.capture());
        Event override = cap.getValue();
        assertThat(override.getRecurrenceParentId()).isEqualTo(EVENT_ID);
        assertThat(override.getRecurrenceId()).isEqualTo(occ);
        assertThat(override.getTitle()).isEqualTo("새 제목");
        assertThat(override.getRrule()).isNull();
    }
}

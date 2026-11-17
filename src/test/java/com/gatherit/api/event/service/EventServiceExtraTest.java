package com.gatherit.api.event.service;

import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.dto.EventUpdateRequest;
import com.gatherit.api.event.entity.DdayPin;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.DdayPinRepository;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.GroupMemberPolicy;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.user.entity.User;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EventService 추가 단위 테스트 — 기존 EventServiceTest 와 중복 없이 아래 경로를 커버:
 *  - create 정상 경로 (EventGroup 다중 삽입/알림/shadowOfEventId/pollId)
 *  - update (groups 변경/필드수정/그림자 cascade/rrule 변경 시 ddayPin 정리)
 *  - delete (그림자 cascade)
 *  - getDetail (정상/그림자 조회)
 *  - findInRange / search (배치 조회/dedup)
 *  - 디데이 핀 (pinDday/unpinDday/setPrimaryDday/listDdayPins)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceExtraTest {

    // ---- 상수 ----
    private static final long USER    = 1L;
    private static final long OTHER   = 2L;
    private static final long EVENT_ID = 10L;
    private static final long SHADOW_ID = 20L;
    private static final long GROUP_A  = 100L;
    private static final long GROUP_B  = 101L;
    private static final long POLL_ID  = 200L;

    private static final Instant START = Instant.parse("2026-07-01T05:00:00Z");
    private static final Instant END   = Instant.parse("2026-07-01T06:00:00Z");

    // ---- 협력자 ----
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
        service = new EventService(eventRepository, ddayPinRepository, eventGroupRepository,
                groupMemberRepository, groupRepository, pollRepository,
                notificationService, userRepository);
    }

    // ======================== helpers ========================

    private Event plainEvent(long id, long createdBy) {
        return plainEvent(id, createdBy, null, null);
    }

    private Event plainEvent(long id, long createdBy, Long shadowOfEventId, Long fromPollId) {
        Event e = Event.builder()
                .title("미팅")
                .startAt(START)
                .endAt(END)
                .createdBy(createdBy)
                .shadowOfEventId(shadowOfEventId)
                .fromPollId(fromPollId)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    // 실제 엔티티로 생성(mock+when 금지) — when() 헬퍼를 when().thenReturn(...) 인자 안에서 호출하면
    // UnfinishedStubbingException 발생하므로, 인라인 호출돼도 안전하도록 빌더 기반으로.
    private EventGroup eg(long eventId, long groupId) {
        return EventGroup.builder().eventId(eventId).groupId(groupId).build();
    }

    private Group normalGroup(long id) {
        Group g = Group.builder()
                .name("그룹").colorKey(GroupColor.team).createdBy(1L)
                .personal(false).eventCreatePolicy(GroupMemberPolicy.ANYONE)
                .build();
        ReflectionTestUtils.setField(g, "id", id);
        ReflectionTestUtils.setField(g, "createdAt", Instant.EPOCH);
        return g;
    }

    private GroupMember gm(long groupId, long userId, GroupColor color) {
        return GroupMember.builder().groupId(groupId).userId(userId).colorKey(color).build();
    }

    private User user(long id, String nick) {
        User u = User.builder().nickname(nick).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    /** EventCreateRequest 편의 생성 */
    private EventCreateRequest createReq(Instant start, Instant end,
                                          List<Long> groupIds, Long pollId, Long shadowOfEventId) {
        return new EventCreateRequest("미팅", start, end, null, null, null,
                groupIds, pollId, shadowOfEventId,
                null, null, null, null, null, null, null, null);
    }

    /** EventUpdateRequest 편의 생성 (타이틀/시간/그룹) */
    private EventUpdateRequest updateReq(String title, Instant startAt, Instant endAt,
                                          List<Long> groupIds, String rrule) {
        return new EventUpdateRequest(title, startAt, endAt, null, null, null,
                groupIds, rrule, null, null, null, null, null, null, null, null, null, null);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ==========================================================
    //  CREATE — 정상 경로
    // ==========================================================

    @Test
    @DisplayName("create 정상 — 두 그룹에 EventGroup 각 1개씩 저장")
    void create_multipleGroups_savesEventGroups() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        Group gB = normalGroup(GROUP_B);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(groupRepository.findById(GROUP_B)).thenReturn(Optional.of(gB));
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", EVENT_ID);
            return e;
        });
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink), gm(GROUP_B, USER, GroupColor.green)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA, gB));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));
        // 알림 대상 조회
        when(groupMemberRepository.findAllByGroupId(anyLong())).thenReturn(List.of());

        EventResponse res = service.create(USER,
                createReq(START, END, List.of(GROUP_A, GROUP_B), null, null));

        assertThat(res).isNotNull();
        assertThat(res.groupIds()).containsExactlyInAnyOrder(GROUP_A, GROUP_B);
        // EventGroup 2개 저장
        verify(eventGroupRepository, times(2)).save(any(EventGroup.class));
    }

    @Test
    @DisplayName("create 정상 — 알림은 생성자 제외 그룹원에게 pushScopedToUsers 호출")
    void create_notify_excludesCreator() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", EVENT_ID);
            return e;
        });
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));

        GroupMember otherMember = gm(GROUP_A, OTHER, GroupColor.team);
        when(groupMemberRepository.findAllByGroupId(GROUP_A)).thenReturn(
                List.of(gm(GROUP_A, USER, GroupColor.pink), otherMember));

        service.create(USER, createReq(START, END, List.of(GROUP_A), null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Long>> recipCaptor = ArgumentCaptor.forClass(Set.class);
        verify(notificationService).pushScopedToUsers(
                recipCaptor.capture(),
                eq(NotificationType.EVENT_CREATED),
                any(), any(), any(), anyList());
        assertThat(recipCaptor.getValue()).containsExactly(OTHER);
    }

    @Test
    @DisplayName("create(notify=false) — 알림 호출 없음")
    void create_notifyFalse_noNotification() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", EVENT_ID);
            return e;
        });
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));

        service.create(USER, createReq(START, END, List.of(GROUP_A), null, null), false);

        verify(notificationService, never()).pushScopedToUsers(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("create — shadowOfEventId 로 그림자 생성 시 원본 없으면 EVENT_NOT_FOUND")
    void create_shadowOfMissingEvent_notFound() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(eventRepository.findById(SHADOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_A), null, SHADOW_ID)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("create — 그림자 위에 그림자 생성 시 EVENT_SHADOW_NESTED")
    void create_nestedShadow_rejected() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));

        // 원본이 이미 그림자(shadowOfEventId != null)
        Event alreadyShadow = plainEvent(SHADOW_ID, OTHER, 999L, null);
        when(eventRepository.findById(SHADOW_ID)).thenReturn(Optional.of(alreadyShadow));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_A), null, SHADOW_ID)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_SHADOW_NESTED);
    }

    @Test
    @DisplayName("create shadow — 원본 시간/RRULE/EXDATE/종일 여부를 상속하고 클라이언트 값을 무시")
    void create_recurringShadow_inheritsSourceTiming() {
        Instant excluded = START.plusSeconds(7 * 86400);
        Event original = Event.builder()
                .title("원본").startAt(START).endAt(END).createdBy(OTHER)
                .rrule("FREQ=WEEKLY;BYDAY=WE").allDay(true).build();
        ReflectionTestUtils.setField(original, "id", EVENT_ID);
        original.addExdate(excluded);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        Group groupB = normalGroup(GROUP_B);
        when(groupRepository.findById(GROUP_B)).thenReturn(Optional.of(groupB));
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_B, USER, GroupColor.green)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(groupB));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", SHADOW_ID);
            return e;
        });

        Instant clientStart = Instant.parse("2026-09-02T05:00:00Z");
        EventCreateRequest req = new EventCreateRequest("B에서 보일 이름", clientStart,
                clientStart.minusSeconds(3600), null, null, null, List.of(GROUP_B),
                null, EVENT_ID, "FREQ=DAILY", null, null, null, null, false, null, null);
        EventResponse response = service.create(USER, req, false);

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        Event shadow = saved.getValue();
        assertThat(shadow.getTitle()).isEqualTo("B에서 보일 이름");
        assertThat(shadow.getStartAt()).isEqualTo(START);
        assertThat(shadow.getEndAt()).isEqualTo(END);
        assertThat(shadow.getRrule()).isEqualTo("FREQ=WEEKLY;BYDAY=WE");
        assertThat(shadow.getExdatesList()).containsExactly(excluded);
        assertThat(shadow.isAllDay()).isTrue();
        assertThat(response.groupIds()).containsExactly(GROUP_B);
        assertThat(response.rrule()).isEqualTo(original.getRrule());
        assertThat(response.exdates()).containsExactly(excluded);
    }

    @Test
    @DisplayName("create shadow — 수정된 회차가 있는 원본은 저장 전에 거부")
    void create_shadowAfterOccurrenceOverride_rejected() {
        Event original = Event.builder()
                .title("원본").startAt(START).endAt(END).createdBy(OTHER)
                .rrule("FREQ=WEEKLY").build();
        ReflectionTestUtils.setField(original, "id", EVENT_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        when(groupRepository.findById(GROUP_B)).thenReturn(Optional.of(normalGroup(GROUP_B)));
        when(eventRepository.findByRecurrenceParentId(EVENT_ID)).thenReturn(List.of(plainEvent(30L, OTHER)));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_B), null, EVENT_ID), false))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("create shadow — 수정된 단일 회차 자체의 공유는 고아 그림자 방지를 위해 거부")
    void create_shadowOfOccurrenceOverride_rejected() {
        Event override = Event.builder()
                .title("수정된 회차").startAt(START).endAt(END).createdBy(OTHER)
                .recurrenceParentId(99L).recurrenceId(START).build();
        ReflectionTestUtils.setField(override, "id", EVENT_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(override));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        when(groupRepository.findById(GROUP_B)).thenReturn(Optional.of(normalGroup(GROUP_B)));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_B), null, EVENT_ID), false))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("create — pollId 미확정 폴이면 POLL_NOT_CONFIRMED")
    void create_pollNotConfirmed() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", EVENT_ID);
            return e;
        });

        Poll poll = mock(Poll.class);
        when(poll.getGroupId()).thenReturn(GROUP_A);
        when(poll.isConfirmed()).thenReturn(false);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_A), POLL_ID, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("create — pollId 이미 연결된 폴이면 POLL_EVENT_ALREADY_LINKED")
    void create_pollAlreadyLinked() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findById(GROUP_A)).thenReturn(Optional.of(gA));
        when(eventRepository.save(any())).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", EVENT_ID);
            return e;
        });

        Poll poll = mock(Poll.class);
        when(poll.getGroupId()).thenReturn(GROUP_A);
        when(poll.isConfirmed()).thenReturn(true);
        when(poll.linkEventOnce(any())).thenReturn(false); // 이미 연결
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_A), POLL_ID, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.POLL_EVENT_ALREADY_LINKED);
    }

    @Test
    @DisplayName("create — 내 다른 그룹 일정으로 확정 투표를 연결해 선점할 수 없음")
    void create_foreignPollGroupRejectedBeforeSave() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        when(groupRepository.findById(GROUP_B)).thenReturn(Optional.of(normalGroup(GROUP_B)));
        Poll poll = mock(Poll.class);
        when(poll.getGroupId()).thenReturn(GROUP_A);
        when(poll.isConfirmed()).thenReturn(true);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_B), POLL_ID, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.POLL_FORBIDDEN);
        verify(eventRepository, never()).save(any());
        verify(eventGroupRepository, never()).save(any());
        verify(pollRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create — 투표 그룹 멤버가 아니면 그룹을 요청에 넣어도 일정 생성 불가")
    void create_pollGroupNonMemberRejectedBeforeSave() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.create(USER,
                createReq(START, END, List.of(GROUP_A), POLL_ID, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
        verify(pollRepository, never()).findById(any());
        verify(eventRepository, never()).save(any());
    }

    // ==========================================================
    //  UPDATE
    // ==========================================================

    @Test
    @DisplayName("update 정상 — 그룹 변경 시 기존 EventGroup 삭제 후 새 그룹으로 재저장")
    void update_groupsChanged_deletesAndResaves() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID))
                .thenReturn(List.of(eg(EVENT_ID, GROUP_A)))  // 이전 그룹
                .thenReturn(List.of(eg(EVENT_ID, GROUP_B))); // 갱신 후
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_B, USER, GroupColor.green)));
        Group gB = normalGroup(GROUP_B);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gB));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());

        service.update(USER, EVENT_ID, updateReq(null, null, null, List.of(GROUP_B), null));

        verify(eventGroupRepository).deleteAllByEventId(EVENT_ID);
        verify(eventGroupRepository).flush();
        verify(eventGroupRepository, atLeastOnce()).save(any(EventGroup.class));
    }

    @Test
    @DisplayName("update 정상 — 이벤트 없으면 EVENT_NOT_FOUND")
    void update_eventNotFound() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER, EVENT_ID,
                updateReq("새 제목", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("update — 종료 시각 역전 시 EVENT_TIME_INVALID")
    void update_endBeforeStart_invalid() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        Instant earlyEnd = Instant.parse("2026-07-01T04:00:00Z");

        assertThatThrownBy(() -> service.update(USER, EVENT_ID,
                updateReq(null, START, earlyEnd, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_TIME_INVALID);
    }

    @Test
    @DisplayName("update — groupIds 빈 리스트 지정 시 EVENT_NO_GROUPS")
    void update_emptyGroups_rejected() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(USER, EVENT_ID,
                updateReq(null, null, null, List.of(), null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NO_GROUPS);
    }

    @Test
    @DisplayName("update — 그룹 변경 시 비멤버 그룹이면 NOT_GROUP_MEMBER")
    void update_groupsChanged_nonMember() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.update(USER, EVENT_ID,
                updateReq(null, null, null, List.of(GROUP_B), null)))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("update — rrule 추가(반복 전환) 시 ddayPin 전부 삭제")
    void update_rruleSet_deletesAllDdayPins() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of());

        service.update(USER, EVENT_ID,
                updateReq(null, null, null, null, "FREQ=WEEKLY;BYDAY=MO"));

        verify(ddayPinRepository).deleteAllByEventId(EVENT_ID);
    }

    @Test
    @DisplayName("update — 원본 시간 변경 시 그림자에 시간 전파")
    void update_timeChange_propagatesToShadows() {
        Event original = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));

        Event shadow = plainEvent(SHADOW_ID, OTHER, (long) EVENT_ID, null);
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of(shadow));

        Instant newEnd = Instant.parse("2026-07-01T07:00:00Z");
        service.update(USER, EVENT_ID, updateReq(null, START, newEnd, null, null));

        // 그림자의 endAt 이 갱신됐는지 — Event.update() 가 호출됐으므로 shadow.endAt == newEnd
        assertThat(shadow.getEndAt()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("update ALL — 반복 규칙 추가와 해제를 그림자에도 전파")
    void update_rruleChangeAndRemoval_propagatesToShadows() {
        Event original = plainEvent(EVENT_ID, USER);
        Event shadow = plainEvent(SHADOW_ID, USER, EVENT_ID, null);
        shadow.updateRrule("FREQ=DAILY");
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of(shadow));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        Group groupA = normalGroup(GROUP_A);
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(groupA));

        service.update(USER, EVENT_ID, updateReq(null, null, null, null, ""));
        assertThat(original.getRrule()).isNull();
        assertThat(shadow.getRrule()).isNull();

        service.update(USER, EVENT_ID, updateReq(null, null, null, null, "FREQ=WEEKLY"));
        assertThat(original.getRrule()).isEqualTo("FREQ=WEEKLY");
        assertThat(shadow.getRrule()).isEqualTo("FREQ=WEEKLY");
    }

    // ==========================================================
    //  DELETE
    // ==========================================================

    @Test
    @DisplayName("delete 정상 — 원본 삭제 시 그림자의 EventGroup/DdayPin/Event 까지 cascade")
    void delete_cascadesShadows() {
        Event original = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));

        Event shadow = plainEvent(SHADOW_ID, OTHER, (long) EVENT_ID, null);
        when(eventRepository.findByShadowOfEventId(EVENT_ID)).thenReturn(List.of(shadow));

        service.delete(USER, EVENT_ID);

        // 그림자 정리
        verify(eventGroupRepository).deleteAllByEventId(SHADOW_ID);
        verify(ddayPinRepository).deleteAllByEventId(SHADOW_ID);
        verify(eventRepository).delete(shadow);
        // 원본 정리
        verify(eventGroupRepository).deleteAllByEventId(EVENT_ID);
        verify(ddayPinRepository).deleteAllByEventId(EVENT_ID);
        verify(eventRepository).delete(original);
        // 죽은 참조 알림 제거
        verify(notificationService).deleteByRefKeys(List.of("event:" + EVENT_ID));
    }

    @Test
    @DisplayName("delete — 투표에서 직접 만든 일정은 연결 ID를 tombstone 으로 유지")
    void delete_fromPoll_keepsLinkedEventId() {
        Event event = plainEvent(EVENT_ID, USER, null, POLL_ID);
        Poll poll = Poll.builder()
                .groupId(GROUP_A).title("투표")
                .deadlineAt(END).confirmPolicy(com.gatherit.api.poll.entity.ConfirmPolicy.ANYONE)
                .createdBy(USER).build();
        poll.linkEventOnce(EVENT_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        service.delete(USER, EVENT_ID);

        verify(eventRepository).delete(event);
        assertThat(poll.getLinkedEventId()).isEqualTo(EVENT_ID);
        assertThat(poll.linkEventOnce(SHADOW_ID)).isFalse();
        verify(pollRepository, never()).save(any());
    }

    @Test
    @DisplayName("delete — 존재하지 않는 이벤트면 EVENT_NOT_FOUND")
    void delete_eventNotFound() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(USER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    // ==========================================================
    //  GET DETAIL
    // ==========================================================

    @Test
    @DisplayName("getDetail 정상 — 원본 조회 시 myShadows 채워 반환")
    void getDetail_original_myShadowsFilled() {
        Event original = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(original));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);

        Event shadow = plainEvent(SHADOW_ID, USER, (long) EVENT_ID, null);
        when(eventRepository.findByShadowOfEventIdAndCreatedBy(EVENT_ID, USER)).thenReturn(List.of(shadow));
        when(eventGroupRepository.findAllByEventIdIn(List.of(SHADOW_ID))).thenReturn(List.of(eg(SHADOW_ID, GROUP_B)));

        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));

        EventResponse res = service.getDetail(USER, EVENT_ID);

        assertThat(res.myShadows()).hasSize(1);
        assertThat(res.myShadows().get(0).id()).isEqualTo(SHADOW_ID);
    }

    @Test
    @DisplayName("getDetail — 그림자 조회 시 originalGroupIds 채워 반환")
    void getDetail_shadow_originalGroupIdsFilled() {
        Event shadow = plainEvent(SHADOW_ID, USER, (long) EVENT_ID, null);
        when(eventRepository.findById(SHADOW_ID)).thenReturn(Optional.of(shadow));
        when(eventGroupRepository.findAllByEventId(SHADOW_ID)).thenReturn(List.of(eg(SHADOW_ID, GROUP_B)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_B, USER)).thenReturn(true);

        // 원본 그룹
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));

        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_B, USER, GroupColor.green)));
        Group gB = normalGroup(GROUP_B);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gB));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user(USER, "닉")));

        EventResponse res = service.getDetail(USER, SHADOW_ID);

        assertThat(res.originalGroupIds()).containsExactly(GROUP_A);
    }

    @Test
    @DisplayName("getDetail — 이벤트 없으면 EVENT_NOT_FOUND")
    void getDetail_notFound() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(USER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    // ==========================================================
    //  FIND IN RANGE
    // ==========================================================

    @Test
    @DisplayName("findInRange — 그림자와 원본 동시 visible 이면 그림자 dedup")
    void findInRange_dedupShadow() {
        Event original = plainEvent(EVENT_ID, USER);
        // 그림자: createdBy=USER, shadowOfEventId=EVENT_ID
        Event shadow = plainEvent(SHADOW_ID, USER, (long) EVENT_ID, null);

        when(eventRepository.findVisibleEventsInRange(eq(USER), any(), any()))
                .thenReturn(List.of(original, shadow));

        // 둘 다의 EventGroup
        when(eventGroupRepository.findAllByEventIdIn(List.of(EVENT_ID, SHADOW_ID))).thenReturn(
                List.of(eg(EVENT_ID, GROUP_A), eg(SHADOW_ID, GROUP_B)));

        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(USER, "닉")));

        List<EventResponse> result = service.findInRange(USER,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"));

        // 그림자가 dedup 돼 원본만 남음
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("findInRange opt-in — 원본과 작성자 그림자 모두 그룹별 응답에 남김")
    void findInRange_optIn_keepsCreatorShadowForGroupView() {
        Event original = plainEvent(EVENT_ID, USER);
        Event shadow = plainEvent(SHADOW_ID, USER, EVENT_ID, null);
        when(eventRepository.findVisibleEventsInRangeIncludingRecurringSources(eq(USER), any(), any()))
                .thenReturn(List.of(original, shadow));
        when(eventGroupRepository.findAllByEventIdIn(List.of(EVENT_ID, SHADOW_ID))).thenReturn(
                List.of(eg(EVENT_ID, GROUP_A), eg(SHADOW_ID, GROUP_B)));
        Group groupA = normalGroup(GROUP_A);
        Group groupB = normalGroup(GROUP_B);
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink), gm(GROUP_B, USER, GroupColor.green)));
        when(groupRepository.findAllById(any())).thenReturn(List.of(groupA, groupB));

        List<EventResponse> result = service.findInRange(USER,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"), true);

        assertThat(result).extracting(EventResponse::id).containsExactly(EVENT_ID, SHADOW_ID);
        assertThat(result.get(0).groupIds()).containsExactly(GROUP_A);
        assertThat(result.get(1).groupIds()).containsExactly(GROUP_B);
    }

    @Test
    @DisplayName("findInRange — 이벤트 없으면 빈 리스트")
    void findInRange_empty() {
        when(eventRepository.findVisibleEventsInRange(any(), any(), any())).thenReturn(List.of());

        List<EventResponse> result = service.findInRange(USER,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-31T23:59:59Z"));

        assertThat(result).isEmpty();
        // N+1 방지 — eventGroupRepository 는 배치 쿼리를 안 날림
        verify(eventGroupRepository, never()).findAllByEventIdIn(any());
    }

    @Test
    @DisplayName("findInRange — 구앱 기본 조회와 신앱 반복 원본 opt-in 은 별도 쿼리")
    void findInRange_recurringSourceCompatibility() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-01T00:00:00Z");

        assertThat(service.findInRange(USER, from, to)).isEmpty();
        assertThat(service.findInRange(USER, from, to, true)).isEmpty();

        verify(eventRepository).findVisibleEventsInRange(USER, from, to);
        verify(eventRepository).findVisibleEventsInRangeIncludingRecurringSources(USER, from, to);
    }

    // ==========================================================
    //  SEARCH
    // ==========================================================

    @Test
    @DisplayName("search — 공백/null 쿼리면 빈 리스트 즉시 반환")
    void search_blankQuery_returnsEmpty() {
        assertThat(service.search(USER, "   ")).isEmpty();
        assertThat(service.search(USER, null)).isEmpty();
        verify(eventRepository, never()).searchVisible(any(), any());
    }

    @Test
    @DisplayName("search — 키워드 있으면 searchVisible 호출 후 응답 매핑")
    void search_withKeyword_returnsResults() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.searchVisible(USER, "미팅")).thenReturn(List.of(event));
        when(eventGroupRepository.findAllByEventIdIn(List.of(EVENT_ID))).thenReturn(
                List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.findAllByUserId(USER)).thenReturn(List.of(
                gm(GROUP_A, USER, GroupColor.pink)));
        Group gA = normalGroup(GROUP_A);
        when(groupRepository.findAllById(any())).thenReturn(List.of(gA));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(USER, "닉")));

        List<EventResponse> result = service.search(USER, "미팅");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(EVENT_ID);
    }

    // ==========================================================
    //  DDAY PIN
    // ==========================================================

    @Test
    @DisplayName("pinDday 정상 — 이미 핀 있으면 save 호출 안 함 (멱등)")
    void pinDday_alreadyPinned_noSave() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);

        DdayPin existingPin = mock(DdayPin.class);
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.of(existingPin));

        service.pinDday(USER, EVENT_ID);

        verify(ddayPinRepository, never()).save(any(DdayPin.class));
    }

    @Test
    @DisplayName("pinDday — 핀 없으면 새 DdayPin 저장")
    void pinDday_notPinned_saves() {
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.empty());

        service.pinDday(USER, EVENT_ID);

        verify(ddayPinRepository).save(any(DdayPin.class));
    }

    @Test
    @DisplayName("pinDday — 반복 일정(rrule 있음)이면 INVALID_INPUT")
    void pinDday_rrule_rejected() {
        Event event = Event.builder()
                .title("반복")
                .startAt(START)
                .endAt(END)
                .createdBy(USER)
                .rrule("FREQ=WEEKLY")
                .build();
        ReflectionTestUtils.setField(event, "id", EVENT_ID);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);

        assertThatThrownBy(() -> service.pinDday(USER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("pinDday — 이벤트 없으면 EVENT_NOT_FOUND")
    void pinDday_eventNotFound() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pinDday(USER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("pinDday — 내 그룹 아닌 일정이면 EVENT_FORBIDDEN")
    void pinDday_notMember_forbidden() {
        Event event = plainEvent(EVENT_ID, OTHER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.pinDday(USER, EVENT_ID))
                .isInstanceOf(CustomException.class)
                .extracting(EventServiceExtraTest::codeOf)
                .isEqualTo(ErrorCode.EVENT_FORBIDDEN);
    }

    @Test
    @DisplayName("unpinDday — 핀 있으면 delete 호출")
    void unpinDday_existing_deletes() {
        DdayPin pin = mock(DdayPin.class);
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.of(pin));

        service.unpinDday(USER, EVENT_ID);

        verify(ddayPinRepository).delete(pin);
    }

    @Test
    @DisplayName("unpinDday — 핀 없어도 예외 없음 (멱등)")
    void unpinDday_notExist_noOp() {
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.empty());

        service.unpinDday(USER, EVENT_ID);

        verify(ddayPinRepository, never()).delete(any());
    }

    @Test
    @DisplayName("setPrimaryDday(true) — 기존 대표 해제 후 대상 핀 대표 설정")
    void setPrimaryDday_true_replacesExistingPrimary() {
        // pinDday 내부 의존
        Event event = plainEvent(EVENT_ID, USER);
        when(eventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_A, USER)).thenReturn(true);

        DdayPin pin = mock(DdayPin.class);
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.of(pin));

        DdayPin oldPrimary = mock(DdayPin.class);
        when(ddayPinRepository.findAllByUserIdAndPrimaryTrue(USER)).thenReturn(List.of(oldPrimary));

        service.setPrimaryDday(USER, EVENT_ID, true);

        verify(oldPrimary).setPrimary(false);
        verify(pin).setPrimary(true);
    }

    @Test
    @DisplayName("setPrimaryDday(false) — 해당 핀의 primary 를 false 로")
    void setPrimaryDday_false_clearsPrimary() {
        DdayPin pin = mock(DdayPin.class);
        when(ddayPinRepository.findByUserIdAndEventId(USER, EVENT_ID)).thenReturn(Optional.of(pin));

        service.setPrimaryDday(USER, EVENT_ID, false);

        verify(pin).setPrimary(false);
    }

    @Test
    @DisplayName("listDdayPins — 핀 없으면 빈 리스트")
    void listDdayPins_empty() {
        when(ddayPinRepository.findAllByUserId(USER)).thenReturn(List.of());

        List<EventService.DdayPinResponse> result = service.listDdayPins(USER);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listDdayPins — 죽은 핀(일정 삭제됨) lazy 정리 후 visible 만 반환")
    void listDdayPins_deadPin_lazyCleaned() {
        long deadEventId = 999L;
        DdayPin deadPin = mock(DdayPin.class);
        when(deadPin.getEventId()).thenReturn(deadEventId);

        DdayPin livePin = mock(DdayPin.class);
        when(livePin.getEventId()).thenReturn(EVENT_ID);

        when(ddayPinRepository.findAllByUserId(USER)).thenReturn(List.of(livePin, deadPin));

        // visible: EVENT_ID 만 보임
        Event liveEvent = plainEvent(EVENT_ID, USER);
        when(eventRepository.findVisibleByIdIn(eq(USER), any())).thenReturn(List.of(liveEvent));
        // existing: EVENT_ID 만 실제 존재 (deadEventId 는 삭제됨)
        when(eventRepository.findAllById(any())).thenReturn(List.of(liveEvent));

        when(eventGroupRepository.findAllByEventId(EVENT_ID)).thenReturn(List.of(eg(EVENT_ID, GROUP_A)));

        List<EventService.DdayPinResponse> result = service.listDdayPins(USER);

        // 죽은 핀 정리
        verify(ddayPinRepository).deleteAllByEventIdIn(argThat(ids -> ids.contains(deadEventId)));
        // live 이벤트만 응답에 포함
        assertThat(result).hasSize(1);
        assertThat(result.get(0).eventId()).isEqualTo(EVENT_ID);
    }
}

package com.gatherit.api.poll.service;

import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.event.entity.Event;
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
import com.gatherit.api.poll.dto.PollCreateRequest;
import com.gatherit.api.poll.dto.PollResponse;
import com.gatherit.api.poll.dto.PollUpdateRequest;
import com.gatherit.api.poll.dto.VoteRequest;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.PollVote;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.poll.repository.PollGuestVoteRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PollService — confirm 제외 메서드 단위 테스트.
 * 커버 대상: create, vote, cancelMyVote, update, delete, getDetail, findByGroup, countPending
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PollServiceCreateVoteTest {

    // ─── 상수 ─────────────────────────────────────────────────────────────────
    private static final long POLL_ID    = 1L;
    private static final long GROUP_ID   = 10L;
    private static final long SLOT_ID_A  = 100L;
    private static final long SLOT_ID_B  = 101L;
    private static final long PROPOSER   = 7L;
    private static final long MEMBER     = 8L;

    // ─── Mock 협력자 ──────────────────────────────────────────────────────────
    @Mock PollRepository        pollRepository;
    @Mock EventRepository       eventRepository;
    @Mock PollSlotRepository    slotRepository;
    @Mock PollVoteRepository    voteRepository;
    @Mock PollGuestParticipantRepository guestParticipantRepository;
    @Mock PollGuestVoteRepository guestVoteRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository       groupRepository;
    @Mock NotificationService   notificationService;

    PollService service;

    @BeforeEach
    void setUp() {
        service = new PollService(
                pollRepository, eventRepository, slotRepository, voteRepository,
                guestParticipantRepository, guestVoteRepository,
                groupMemberRepository, groupRepository,
                notificationService);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    /** 미확정 Poll 생성. */
    private Poll buildPoll(long createdBy, ConfirmPolicy policy) {
        Poll p = Poll.builder()
                .groupId(GROUP_ID)
                .title("저녁 약속")
                .deadlineAt(Instant.parse("2099-12-31T23:59:59Z"))   // 마감 미래
                .confirmPolicy(policy)
                .createdBy(createdBy)
                .build();
        ReflectionTestUtils.setField(p, "id", POLL_ID);
        return p;
    }

    /** 마감이 이미 지난 Poll. */
    private Poll buildExpiredPoll() {
        Poll p = Poll.builder()
                .groupId(GROUP_ID)
                .title("지난 투표")
                .deadlineAt(Instant.parse("2000-01-01T00:00:00Z"))   // 마감 과거
                .confirmPolicy(ConfirmPolicy.ANYONE)
                .createdBy(PROPOSER)
                .build();
        ReflectionTestUtils.setField(p, "id", POLL_ID);
        return p;
    }

    private PollSlot buildSlot(long slotId) {
        PollSlot s = PollSlot.builder()
                .pollId(POLL_ID)
                .startAt(Instant.parse("2099-12-31T10:00:00Z"))
                .endAt(Instant.parse("2099-12-31T11:00:00Z"))
                .build();
        ReflectionTestUtils.setField(s, "id", slotId);
        return s;
    }

    private Group buildGroup(boolean personal, GroupMemberPolicy pollPolicy) {
        Group g = Group.builder()
                .name("가족")
                .colorKey(GroupColor.family)
                .createdBy(PROPOSER)
                .personal(personal)
                .pollCreatePolicy(pollPolicy)
                .build();
        ReflectionTestUtils.setField(g, "id", GROUP_ID);
        return g;
    }

    private GroupMember memberOf(long userId) {
        GroupMember m = mock(GroupMember.class);
        when(m.getUserId()).thenReturn(userId);
        return m;
    }

    /** buildDetail stub — create/getDetail/update 가 내부적으로 호출 */
    private void stubBuildDetail(PollSlot slot) {
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slot));
        when(voteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
        when(groupMemberRepository.countByGroupId(GROUP_ID)).thenReturn(3L);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  create()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("create — 정상: 슬롯 저장 + POLL_CREATED 알림 발송")
    void create_happy() {
        Group group = buildGroup(false, GroupMemberPolicy.ANYONE);
        PollSlot slot = buildSlot(SLOT_ID_A);

        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, PROPOSER)).thenReturn(true);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        // save(poll) → id 주입
        doAnswer(inv -> {
            Poll p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", POLL_ID);
            return p;
        }).when(pollRepository).save(any(Poll.class));
        doReturn(slot).when(slotRepository).save(any(PollSlot.class));
        // member mock 은 when() 밖에서 미리 생성 — memberOf() 내부 when() 이 stubbing 중첩되지 않도록
        GroupMember gm1 = memberOf(PROPOSER);
        GroupMember gm2 = memberOf(MEMBER);
        when(groupMemberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of(gm1, gm2));

        // buildDetail
        stubBuildDetail(slot);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(buildPoll(PROPOSER, ConfirmPolicy.ANYONE)));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, PROPOSER)).thenReturn(true);

        PollCreateRequest req = new PollCreateRequest(
                GROUP_ID, "저녁 약속",
                Instant.parse("2099-12-31T23:59:59Z"),
                ConfirmPolicy.ANYONE,
                List.of(new PollCreateRequest.SlotInput(
                        Instant.parse("2099-12-31T10:00:00Z"),
                        Instant.parse("2099-12-31T11:00:00Z")))
        );

        PollResponse res = service.create(PROPOSER, req);

        assertThat(res).isNotNull();
        verify(pollRepository).save(any(Poll.class));
        verify(slotRepository, atLeastOnce()).save(any(PollSlot.class));
        verify(notificationService).pushScopedToUsers(
                argThat(targets -> targets.contains(MEMBER) && !targets.contains(PROPOSER)),
                eq(NotificationType.POLL_CREATED), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("create — 그룹 멤버 아님 → NOT_GROUP_MEMBER")
    void create_notMember_throws() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(false);

        PollCreateRequest req = new PollCreateRequest(
                GROUP_ID, "테스트",
                Instant.parse("2099-12-31T23:59:59Z"),
                ConfirmPolicy.ANYONE,
                List.of(new PollCreateRequest.SlotInput(
                        Instant.parse("2099-12-31T10:00:00Z"),
                        Instant.parse("2099-12-31T11:00:00Z")))
        );

        assertThatThrownBy(() -> service.create(MEMBER, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);

        verify(pollRepository, never()).save(any());
    }

    @Test
    @DisplayName("create — 개인 캘린더 그룹 → PERSONAL_GROUP_NO_POLL")
    void create_personalGroup_throws() {
        Group group = buildGroup(true, GroupMemberPolicy.ANYONE);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, PROPOSER)).thenReturn(true);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        PollCreateRequest req = new PollCreateRequest(
                GROUP_ID, "테스트",
                Instant.parse("2099-12-31T23:59:59Z"),
                ConfirmPolicy.ANYONE,
                List.of(new PollCreateRequest.SlotInput(
                        Instant.parse("2099-12-31T10:00:00Z"),
                        Instant.parse("2099-12-31T11:00:00Z")))
        );

        assertThatThrownBy(() -> service.create(PROPOSER, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.PERSONAL_GROUP_NO_POLL);
    }

    @Test
    @DisplayName("create — OWNER_ONLY 정책 + 비오너 → GROUP_POLL_CREATE_FORBIDDEN")
    void create_ownerOnlyPolicy_nonOwner_throws() {
        // PROPOSER 가 그룹 owner, MEMBER 는 비오너
        Group group = buildGroup(false, GroupMemberPolicy.OWNER_ONLY);
        // group.createdBy == PROPOSER, MEMBER != PROPOSER → isOwner(MEMBER) == false
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));

        PollCreateRequest req = new PollCreateRequest(
                GROUP_ID, "테스트",
                Instant.parse("2099-12-31T23:59:59Z"),
                ConfirmPolicy.ANYONE,
                List.of(new PollCreateRequest.SlotInput(
                        Instant.parse("2099-12-31T10:00:00Z"),
                        Instant.parse("2099-12-31T11:00:00Z")))
        );

        assertThatThrownBy(() -> service.create(MEMBER, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.GROUP_POLL_CREATE_FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  vote()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("vote — 정상: 신규 PollVote 저장")
    void vote_newVote_saved() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slotA = buildSlot(SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slotA));
        when(voteRepository.findById(any(PollVote.PK.class))).thenReturn(Optional.empty());

        VoteRequest req = new VoteRequest(
                List.of(new VoteRequest.SlotVote(SLOT_ID_A, true))
        );
        service.vote(MEMBER, POLL_ID, req);

        verify(voteRepository).save(any(PollVote.class));
    }

    @Test
    @DisplayName("vote — 기존 응답 수정: setAvailable 호출")
    void vote_existingVote_updated() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slotA = buildSlot(SLOT_ID_A);
        PollVote existing = mock(PollVote.class);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slotA));
        when(voteRepository.findById(new PollVote.PK(POLL_ID, SLOT_ID_A, MEMBER)))
                .thenReturn(Optional.of(existing));

        VoteRequest req = new VoteRequest(
                List.of(new VoteRequest.SlotVote(SLOT_ID_A, false))
        );
        service.vote(MEMBER, POLL_ID, req);

        verify(existing).setAvailable(false);
        verify(voteRepository, never()).save(any());
    }

    @Test
    @DisplayName("vote — 확정된 투표 → POLL_ALREADY_CONFIRMED")
    void vote_confirmed_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);

        VoteRequest req = new VoteRequest(List.of(new VoteRequest.SlotVote(SLOT_ID_A, true)));

        assertThatThrownBy(() -> service.vote(MEMBER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_ALREADY_CONFIRMED);
    }

    @Test
    @DisplayName("vote — 마감 지난 투표 → POLL_DEADLINE_PASSED")
    void vote_deadlinePassed_throws() {
        Poll poll = buildExpiredPoll();

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);

        VoteRequest req = new VoteRequest(List.of(new VoteRequest.SlotVote(SLOT_ID_A, true)));

        assertThatThrownBy(() -> service.vote(MEMBER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_DEADLINE_PASSED);
    }

    @Test
    @DisplayName("vote — 존재하지 않는 슬롯 ID 포함 → POLL_SLOT_NOT_FOUND")
    void vote_invalidSlotId_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slotA = buildSlot(SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slotA));

        long invalidSlotId = 9999L;
        VoteRequest req = new VoteRequest(List.of(new VoteRequest.SlotVote(invalidSlotId, true)));

        assertThatThrownBy(() -> service.vote(MEMBER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_SLOT_NOT_FOUND);
    }

    @Test
    @DisplayName("vote — 그룹 멤버 아님 → POLL_FORBIDDEN")
    void vote_notMember_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(false);

        VoteRequest req = new VoteRequest(List.of(new VoteRequest.SlotVote(SLOT_ID_A, true)));

        assertThatThrownBy(() -> service.vote(MEMBER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  cancelMyVote()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cancelMyVote — 정상: deleteByPollIdAndUserId 호출")
    void cancelMyVote_happy() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);

        service.cancelMyVote(MEMBER, POLL_ID);

        verify(voteRepository).deleteByPollIdAndUserId(POLL_ID, MEMBER);
    }

    @Test
    @DisplayName("cancelMyVote — 이미 확정된 투표 → POLL_ALREADY_CONFIRMED")
    void cancelMyVote_confirmed_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);

        assertThatThrownBy(() -> service.cancelMyVote(MEMBER, POLL_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_ALREADY_CONFIRMED);

        verify(voteRepository, never()).deleteByPollIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("cancelMyVote — 폴 없음 → POLL_NOT_FOUND")
    void cancelMyVote_pollNotFound_throws() {
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelMyVote(MEMBER, POLL_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_FOUND);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  update()  — editBasic 래퍼
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("update — 발의자가 title/deadline/policy 수정 성공")
    void update_byProposer_success() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slot = buildSlot(SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, PROPOSER)).thenReturn(true);
        stubBuildDetail(slot);

        PollUpdateRequest req = new PollUpdateRequest(
                "수정된 제목",
                Instant.parse("2099-12-30T23:59:59Z"),
                ConfirmPolicy.PROPOSER_ONLY
        );

        PollResponse res = service.update(PROPOSER, POLL_ID, req);

        assertThat(res).isNotNull();
        assertThat(poll.getTitle()).isEqualTo("수정된 제목");
        assertThat(poll.getConfirmPolicy()).isEqualTo(ConfirmPolicy.PROPOSER_ONLY);
    }

    @Test
    @DisplayName("update — 발의자가 아닌 멤버가 수정 시도 → POLL_NOT_PROPOSER")
    void update_nonProposer_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        PollUpdateRequest req = new PollUpdateRequest("제목", null, null);

        assertThatThrownBy(() -> service.update(MEMBER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_PROPOSER);
    }

    @Test
    @DisplayName("update — 이미 확정된 투표 → POLL_ALREADY_CONFIRMED")
    void update_confirmed_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", SLOT_ID_A);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        PollUpdateRequest req = new PollUpdateRequest("제목", null, null);

        assertThatThrownBy(() -> service.update(PROPOSER, POLL_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_ALREADY_CONFIRMED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  delete()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("delete — 발의자가 삭제: vote/slot/poll 모두 삭제 + 알림 refKey 정리")
    void delete_byProposer_success() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        service.delete(PROPOSER, POLL_ID);

        verify(voteRepository).deleteAllByPollId(POLL_ID);
        verify(slotRepository).deleteAllByPollId(POLL_ID);
        verify(pollRepository).delete(poll);
        verify(notificationService).deleteByRefKeys(List.of("poll:" + POLL_ID));
    }

    @Test
    @DisplayName("delete — 발의자가 아닌 멤버가 삭제 시도 → POLL_NOT_PROPOSER")
    void delete_nonProposer_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.delete(MEMBER, POLL_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_PROPOSER);

        verify(pollRepository, never()).delete(any());
    }

    @Test
    @DisplayName("delete — 폴 없음 → POLL_NOT_FOUND")
    void delete_pollNotFound_throws() {
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(PROPOSER, POLL_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_FOUND);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  getDetail()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getDetail — 정상: SlotResult 포함 응답 반환")
    void getDetail_happy() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slot = buildSlot(SLOT_ID_A);

        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slot));
        when(voteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
        when(groupMemberRepository.countByGroupId(GROUP_ID)).thenReturn(5L);

        PollResponse res = service.getDetail(MEMBER, POLL_ID);

        assertThat(res).isNotNull();
        assertThat(res.slots()).hasSize(1);
        assertThat(res.totalMembers()).isEqualTo(5);
    }

    @Test
    @DisplayName("getDetail — 직접 삭제된 연결 일정은 삭제됨으로 표시하고 재등록을 막음")
    void getDetail_deletedLinkedEvent() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        poll.confirm(SLOT_ID_A);
        poll.linkEventOnce(42L);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(groupMemberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
        when(eventRepository.existsById(42L)).thenReturn(false);

        PollResponse res = service.getDetail(MEMBER, POLL_ID);

        assertThat(res.linkedEventDeleted()).isTrue();
        assertThat(res.linkedEventId()).isEqualTo(42L);
        assertThat(poll.linkEventOnce(43L)).isFalse();
    }

    @Test
    @DisplayName("getDetail — 그룹 멤버 아님 → POLL_FORBIDDEN")
    void getDetail_notMember_throws() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(false);

        assertThatThrownBy(() -> service.getDetail(MEMBER, POLL_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.POLL_FORBIDDEN);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  findByGroup()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("findByGroup — 정상: 그룹 투표 목록 반환 (N+1 일괄 쿼리)")
    void findByGroup_happy() {
        Poll poll = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slot = buildSlot(SLOT_ID_A);

        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(pollRepository.findAllByGroupIdOrderByCreatedAtDesc(GROUP_ID)).thenReturn(List.of(poll));
        when(slotRepository.findAllByPollIdInOrderByStartAtAsc(List.of(POLL_ID))).thenReturn(List.of(slot));
        when(voteRepository.findAllByPollIdIn(List.of(POLL_ID))).thenReturn(List.of());
        when(groupMemberRepository.countByGroupId(GROUP_ID)).thenReturn(4L);

        List<PollResponse> result = service.findByGroup(MEMBER, GROUP_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalMembers()).isEqualTo(4);
        // N+1 방지: IN-query 단 1번씩
        verify(slotRepository).findAllByPollIdInOrderByStartAtAsc(any());
        verify(voteRepository).findAllByPollIdIn(any());
    }

    @Test
    @DisplayName("findByGroup — 연결 일정 존재 여부를 일괄 조회해 삭제 상태 구분")
    void findByGroup_linkedEventStates() {
        Poll active = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        active.linkEventOnce(42L);
        Poll deleted = buildPoll(PROPOSER, ConfirmPolicy.ANYONE);
        ReflectionTestUtils.setField(deleted, "id", 2L);
        deleted.linkEventOnce(43L);
        Event existing = org.mockito.Mockito.mock(Event.class);
        when(existing.getId()).thenReturn(42L);
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(pollRepository.findAllByGroupIdOrderByCreatedAtDesc(GROUP_ID)).thenReturn(List.of(active, deleted));
        when(eventRepository.findAllById(any())).thenReturn(List.of(existing));

        List<PollResponse> result = service.findByGroup(MEMBER, GROUP_ID);

        assertThat(result).extracting(PollResponse::linkedEventDeleted).containsExactly(false, true);
        verify(eventRepository).findAllById(any());
    }

    @Test
    @DisplayName("findByGroup — 그룹 투표 0건 → 빈 리스트")
    void findByGroup_empty() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(true);
        when(pollRepository.findAllByGroupIdOrderByCreatedAtDesc(GROUP_ID)).thenReturn(List.of());

        List<PollResponse> result = service.findByGroup(MEMBER, GROUP_ID);

        assertThat(result).isEmpty();
        verify(slotRepository, never()).findAllByPollIdInOrderByStartAtAsc(any());
    }

    @Test
    @DisplayName("findByGroup — 그룹 멤버 아님 → NOT_GROUP_MEMBER")
    void findByGroup_notMember_throws() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, MEMBER)).thenReturn(false);

        assertThatThrownBy(() -> service.findByGroup(MEMBER, GROUP_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceCreateVoteTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  countPending()
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("countPending — 레포지토리 반환값을 그대로 리턴")
    void countPending_delegatesToRepository() {
        when(pollRepository.countPendingForUser(eq(MEMBER), any(Instant.class))).thenReturn(3L);

        long count = service.countPending(MEMBER);

        assertThat(count).isEqualTo(3L);
        verify(pollRepository).countPendingForUser(eq(MEMBER), any(Instant.class));
    }
}

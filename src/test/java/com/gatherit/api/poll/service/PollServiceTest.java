package com.gatherit.api.poll.service;

import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.poll.dto.ConfirmRequest;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollSlot;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PollService.confirm() 단위 테스트 — 면접에서 설명할 핵심 로직 집중:
 *  - 낙관적 락 동시 확정(race) → POLL_CONFIRM_RACE 변환
 *  - POLL_COMPLETED(발의자 알림)는 "확정자 ≠ 발의자"일 때만
 *  - POLL_CONFIRMED 는 응답자(없으면 전체 멤버)에게, 확정자 본인은 제외
 *  - PROPOSER_ONLY 정책 / 이미 확정된 폴 가드
 * repo/알림은 mock. (LENIENT — 분기마다 도달 지점이 달라 미사용 stub 허용)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PollServiceTest {

    private static final long POLL_ID = 1L;
    private static final long GROUP_ID = 10L;
    private static final long SLOT_ID = 100L;
    private static final long PROPOSER = 7L;
    private static final long CONFIRMER = 8L;
    private static final long RESP_A = 20L;
    private static final long RESP_B = 21L;

    @Mock PollRepository pollRepository;
    @Mock EventRepository eventRepository;
    @Mock PollSlotRepository slotRepository;
    @Mock PollVoteRepository voteRepository;
    @Mock PollGuestParticipantRepository guestParticipantRepository;
    @Mock PollGuestVoteRepository guestVoteRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository groupRepository;
    @Mock NotificationService notificationService;

    PollService service;

    @BeforeEach
    void setUp() {
        service = new PollService(pollRepository, eventRepository, slotRepository, voteRepository,
                guestParticipantRepository, guestVoteRepository,
                groupMemberRepository, groupRepository, notificationService);
    }

    // ---- helpers ----

    private Poll poll(long createdBy, ConfirmPolicy policy) {
        Poll p = Poll.builder()
                .groupId(GROUP_ID).title("저녁 약속")
                .deadlineAt(Instant.parse("2026-06-20T10:00:00Z"))
                .confirmPolicy(policy).createdBy(createdBy)
                .build();
        ReflectionTestUtils.setField(p, "id", POLL_ID);
        return p;
    }

    private PollSlot slotMock() {
        PollSlot slot = mock(PollSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);
        when(slot.getPollId()).thenReturn(POLL_ID);
        when(slot.getStartAt()).thenReturn(Instant.parse("2026-06-20T05:00:00Z"));
        when(slot.getEndAt()).thenReturn(Instant.parse("2026-06-20T06:00:00Z"));
        return slot;
    }

    private GroupMember member(long userId) {
        GroupMember m = mock(GroupMember.class);
        when(m.getUserId()).thenReturn(userId);
        return m;
    }

    /** confirm() 정상 경로(저장+그룹조회+buildDetail) stub. respondents 는 테스트가 따로 지정. */
    private void stubConfirmPath(Poll poll, PollSlot slot, long confirmerId) {
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, confirmerId)).thenReturn(true);
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(pollRepository.saveAndFlush(poll)).thenReturn(poll);
        Group group = mock(Group.class);
        when(group.getName()).thenReturn("가족");
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group));
        // buildDetail
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slot));
        when(voteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
        when(groupMemberRepository.countByGroupId(GROUP_ID)).thenReturn(3L);
    }

    private ConfirmRequest req() {
        return new ConfirmRequest(SLOT_ID);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ---- tests ----

    @Test
    @DisplayName("비발의자 확정 → 발의자에 POLL_COMPLETED, 응답자(확정자 제외)에 POLL_CONFIRMED")
    void confirmByNonProposer_notifiesProposerAndRespondents() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        stubConfirmPath(poll, slotMock(), CONFIRMER);
        when(voteRepository.findRespondentUserIds(POLL_ID))
                .thenReturn(List.of(CONFIRMER, RESP_A, RESP_B));

        service.confirm(CONFIRMER, POLL_ID, req());

        assertThat(poll.getConfirmedSlotId()).isEqualTo(SLOT_ID);
        verify(notificationService)
                .pushPollCompleted(eq(PROPOSER), anyString(), anyString(), eq("poll:" + POLL_ID));
        // POLL_CONFIRMED 는 응답자(확정자 제외)에게 배치 1회 — 대상 집합에 RESP_A/RESP_B 포함, CONFIRMER 제외
        verify(notificationService).pushScopedToUsers(
                argThat(s -> s.contains(RESP_A) && s.contains(RESP_B) && !s.contains(CONFIRMER)),
                eq(NotificationType.POLL_CONFIRMED), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("발의자 본인이 확정 → POLL_COMPLETED 안 보냄")
    void confirmByProposer_doesNotSendPollCompleted() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        stubConfirmPath(poll, slotMock(), PROPOSER);
        when(voteRepository.findRespondentUserIds(POLL_ID)).thenReturn(List.of(RESP_A));

        service.confirm(PROPOSER, POLL_ID, req());

        verify(notificationService, never()).pushPollCompleted(anyLong(), any(), any(), any());
        verify(notificationService).pushScopedToUsers(
                argThat(s -> s.contains(RESP_A)),
                eq(NotificationType.POLL_CONFIRMED), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("응답자 0명 → 그룹 전체 멤버(확정자 제외)에게 POLL_CONFIRMED")
    void confirmWithNoRespondents_notifiesAllMembersExceptConfirmer() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        stubConfirmPath(poll, slotMock(), CONFIRMER);
        when(voteRepository.findRespondentUserIds(POLL_ID)).thenReturn(List.of());
        // 멤버 mock 은 when() 밖에서 미리 생성 (member() 내부의 when() 이 stubbing 중첩되지 않도록)
        GroupMember m1 = member(CONFIRMER);
        GroupMember m2 = member(RESP_A);
        GroupMember m3 = member(RESP_B);
        when(groupMemberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of(m1, m2, m3));

        service.confirm(CONFIRMER, POLL_ID, req());

        // 응답자 0명 → 전원(확정자 제외)에게 배치 1회: RESP_A/RESP_B 포함, CONFIRMER 제외
        verify(notificationService).pushScopedToUsers(
                argThat(s -> s.contains(RESP_A) && s.contains(RESP_B) && !s.contains(CONFIRMER)),
                eq(NotificationType.POLL_CONFIRMED), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("동시 확정(낙관적 락 충돌) → POLL_CONFIRM_RACE, 알림 안 나감")
    void confirmRace_mappedToRaceError() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        PollSlot slot = slotMock();
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, CONFIRMER)).thenReturn(true);
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(slot));
        when(pollRepository.saveAndFlush(poll))
                .thenThrow(new ObjectOptimisticLockingFailureException(Poll.class, POLL_ID));

        assertThatThrownBy(() -> service.confirm(CONFIRMER, POLL_ID, req()))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceTest::codeOf)
                .isEqualTo(ErrorCode.POLL_CONFIRM_RACE);

        verify(notificationService, never())
                .pushScoped(anyLong(), any(), any(), any(), any(), anyCollection());
        verify(notificationService, never()).pushPollCompleted(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("PROPOSER_ONLY 정책 + 비발의자 확정 시도 → POLL_NOT_PROPOSER (저장 안 됨)")
    void proposerOnlyPolicy_nonProposer_throws() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.PROPOSER_ONLY);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, CONFIRMER)).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(CONFIRMER, POLL_ID, req()))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceTest::codeOf)
                .isEqualTo(ErrorCode.POLL_NOT_PROPOSER);

        verify(pollRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).pushPollCompleted(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 확정된 폴 재확정 → POLL_ALREADY_CONFIRMED")
    void alreadyConfirmed_throws() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", 99L);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, PROPOSER)).thenReturn(true);

        assertThatThrownBy(() -> service.confirm(PROPOSER, POLL_ID, req()))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceTest::codeOf)
                .isEqualTo(ErrorCode.POLL_ALREADY_CONFIRMED);
    }

    @Test
    @DisplayName("그룹 멤버 아님 → POLL_FORBIDDEN")
    void notGroupMember_throws() {
        Poll poll = poll(PROPOSER, ConfirmPolicy.ANYONE);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_ID, CONFIRMER)).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(CONFIRMER, POLL_ID, req()))
                .isInstanceOf(CustomException.class)
                .extracting(PollServiceTest::codeOf)
                .isEqualTo(ErrorCode.POLL_FORBIDDEN);
    }
}

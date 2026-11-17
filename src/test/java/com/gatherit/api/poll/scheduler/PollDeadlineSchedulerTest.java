package com.gatherit.api.poll.scheduler;

import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.poll.service.PollService;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * PollDeadlineScheduler 단위 테스트.
 *
 * 검증 포인트:
 *  1. cleanupDeadPolls() 호출 순서 — notify 먼저, cleanup 나중
 *  2. notifyDeadlineReachedPolls — deadline 지난 미확정 폴 발의자에게 push 1회 + deadlineNotified 플래그 세팅
 *  3. cleanupDeadPolls — 마지막 슬롯 endAt 지난 미확정 폴 삭제 (vote → slot → poll 순)
 *  4. cleanupDeadPolls — 마지막 슬롯 endAt 미래인 폴은 삭제 안 함
 *  5. cleanupDeadPolls — 슬롯 없는 폴은 삭제 안 함
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PollDeadlineSchedulerTest {

    /* ── PollService 협력자 ── */
    @Mock PollRepository pollRepository;
    @Mock PollSlotRepository slotRepository;
    @Mock PollVoteRepository voteRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository groupRepository;
    @Mock NotificationService notificationService;

    /* ── 스케줄러가 wrapping 하는 서비스 ── */
    @InjectMocks PollService pollService;

    // ── 헬퍼 ────────────────────────────────────────────

    private Poll unconfirmedPoll(long id, long createdBy, Instant deadlineAt) {
        Poll poll = Poll.builder()
                .groupId(10L)
                .title("회의 일정 투표")
                .deadlineAt(deadlineAt)
                .confirmPolicy(ConfirmPolicy.PROPOSER_ONLY)
                .createdBy(createdBy)
                .build();
        ReflectionTestUtils.setField(poll, "id", id);
        ReflectionTestUtils.setField(poll, "deadlineNotified", false);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", null);
        ReflectionTestUtils.setField(poll, "version", 0L);
        return poll;
    }

    private PollSlot slot(long pollId, Instant startAt, Instant endAt) {
        PollSlot s = PollSlot.builder()
                .pollId(pollId)
                .startAt(startAt)
                .endAt(endAt)
                .build();
        ReflectionTestUtils.setField(s, "id", pollId * 100);
        return s;
    }

    // ── 스케줄러 호출 순서 ───────────────────────────────

    @Test
    @DisplayName("cleanupDeadPolls() — notifyDeadlineReachedPolls 먼저, cleanupDeadPolls 나중 호출")
    void scheduler_callOrder() {
        // 스케줄러는 PollService 의 두 메서드를 순서대로 호출
        PollService mockService = mock(PollService.class);
        PollDeadlineScheduler scheduler = new PollDeadlineScheduler(mockService);

        scheduler.cleanupDeadPolls();

        var inOrder = inOrder(mockService);
        inOrder.verify(mockService).notifyDeadlineReachedPolls();
        inOrder.verify(mockService).cleanupDeadPolls();
    }

    // ── notifyDeadlineReachedPolls ───────────────────────

    @Test
    @DisplayName("notify — deadline 지난 미확정 폴 → pushPollCompleted 1회 + deadlineNotified=true")
    void notify_deadlinePassed_pushSentAndFlagSet() {
        Instant past = Instant.now().minusSeconds(3600);
        Poll poll = unconfirmedPoll(1L, 42L, past);

        when(pollRepository.findAllByConfirmedSlotIdIsNullAndDeadlineNotifiedFalseAndDeadlineAtBefore(any()))
                .thenReturn(List.of(poll));
        when(groupRepository.findById(10L)).thenReturn(java.util.Optional.empty());

        pollService.notifyDeadlineReachedPolls();

        verify(notificationService, times(1))
                .pushPollCompleted(eq(42L), any(), any(), eq("poll:1"));
        // deadlineNotified 플래그가 세팅되었는지
        assert poll.isDeadlineNotified() : "deadlineNotified 가 true 여야 함";
    }

    @Test
    @DisplayName("notify — 대상 폴 없으면 push 미호출")
    void notify_noTarget_noPush() {
        when(pollRepository.findAllByConfirmedSlotIdIsNullAndDeadlineNotifiedFalseAndDeadlineAtBefore(any()))
                .thenReturn(List.of());

        pollService.notifyDeadlineReachedPolls();

        verify(notificationService, never()).pushPollCompleted(any(), any(), any(), any());
    }

    @Test
    @DisplayName("notify — 여러 폴 → 각각 1회씩 push")
    void notify_multiplePolls_eachPushedOnce() {
        Instant past = Instant.now().minusSeconds(3600);
        Poll p1 = unconfirmedPoll(1L, 10L, past);
        Poll p2 = unconfirmedPoll(2L, 20L, past);

        when(pollRepository.findAllByConfirmedSlotIdIsNullAndDeadlineNotifiedFalseAndDeadlineAtBefore(any()))
                .thenReturn(List.of(p1, p2));
        when(groupRepository.findById(anyLong())).thenReturn(java.util.Optional.empty());

        pollService.notifyDeadlineReachedPolls();

        verify(notificationService, times(2)).pushPollCompleted(any(), any(), any(), any());
    }

    // ── cleanupDeadPolls ─────────────────────────────────

    @Test
    @DisplayName("cleanup — 마지막 슬롯 endAt 지난 미확정 폴 → vote/slot/poll 삭제")
    void cleanup_lastSlotExpired_deleted() {
        Instant past = Instant.now().minusSeconds(7200);
        Poll poll = unconfirmedPoll(5L, 99L, past);

        when(pollRepository.findAllByConfirmedSlotIdIsNull()).thenReturn(List.of(poll));
        PollSlot expired = slot(5L, past.minusSeconds(3600), past); // endAt = past
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(5L)).thenReturn(List.of(expired));

        pollService.cleanupDeadPolls();

        verify(voteRepository).deleteAllByPollId(5L);
        verify(slotRepository).deleteAllByPollId(5L);
        verify(pollRepository).delete(poll);
    }

    @Test
    @DisplayName("cleanup — 마지막 슬롯 endAt 미래인 미확정 폴 → 삭제 안 함")
    void cleanup_lastSlotFuture_notDeleted() {
        Instant deadlineInPast = Instant.now().minusSeconds(3600);
        Poll poll = unconfirmedPoll(6L, 99L, deadlineInPast);

        when(pollRepository.findAllByConfirmedSlotIdIsNull()).thenReturn(List.of(poll));
        Instant future = Instant.now().plusSeconds(86400);
        PollSlot notExpired = slot(6L, future.minusSeconds(3600), future); // endAt = future
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(6L)).thenReturn(List.of(notExpired));

        pollService.cleanupDeadPolls();

        verify(pollRepository, never()).delete(any());
        verify(voteRepository, never()).deleteAllByPollId(anyLong());
        verify(slotRepository, never()).deleteAllByPollId(anyLong());
    }

    @Test
    @DisplayName("cleanup — 슬롯이 아예 없는 미확정 폴 → 삭제 안 함")
    void cleanup_noSlots_notDeleted() {
        Poll poll = unconfirmedPoll(7L, 99L, Instant.now().minusSeconds(3600));

        when(pollRepository.findAllByConfirmedSlotIdIsNull()).thenReturn(List.of(poll));
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(7L)).thenReturn(List.of());

        pollService.cleanupDeadPolls();

        verify(pollRepository, never()).delete(any());
    }

    @Test
    @DisplayName("cleanup — 미확정 폴 없으면 삭제 0건")
    void cleanup_noUnconfirmed_noDeletion() {
        when(pollRepository.findAllByConfirmedSlotIdIsNull()).thenReturn(List.of());

        pollService.cleanupDeadPolls();

        verify(pollRepository, never()).delete(any());
    }

    @Test
    @DisplayName("cleanup — 슬롯 여럿일 때 마지막(최신) 슬롯 기준으로 판단")
    void cleanup_multipleSlots_lastSlotDecides() {
        Instant now = Instant.now();
        Poll poll = unconfirmedPoll(8L, 99L, now.minusSeconds(3600));

        when(pollRepository.findAllByConfirmedSlotIdIsNull()).thenReturn(List.of(poll));

        // 첫 슬롯은 이미 지났지만 마지막 슬롯은 미래
        PollSlot first  = slot(8L, now.minusSeconds(7200), now.minusSeconds(3600));
        PollSlot second = slot(8L, now.plusSeconds(3600),  now.plusSeconds(7200));
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(8L)).thenReturn(List.of(first, second));

        pollService.cleanupDeadPolls();

        // 마지막 슬롯이 미래이므로 삭제 안 함
        verify(pollRepository, never()).delete(any());
    }
}

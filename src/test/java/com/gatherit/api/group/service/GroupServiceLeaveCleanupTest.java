package com.gatherit.api.group.service;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.poll.repository.PollGuestVoteRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.user.repository.UserRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 그룹 탈퇴(leave) cleanup 전 경우 커버.
 * 정책: 탈퇴자가 유발한 것은 전부 제거, 남이 createdBy 인 것은 유지(죽은 참조만 끊음).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupServiceLeaveCleanupTest {

    private static final long A = 1L;       // 탈퇴자
    private static final long B = 2L;       // 다른 멤버
    private static final long GROUP = 10L;  // 떠나는 그룹

    @Mock GroupRepository groupRepository;
    @Mock GroupMemberRepository memberRepository;
    @Mock UserRepository userRepository;
    @Mock InviteTokenStore inviteTokenStore;
    @Mock EventRepository eventRepository;
    @Mock EventGroupRepository eventGroupRepository;
    @Mock NotificationService notificationService;
    @Mock PollRepository pollRepository;
    @Mock PollSlotRepository pollSlotRepository;
    @Mock PollVoteRepository pollVoteRepository;
    @Mock PollGuestParticipantRepository pollGuestParticipantRepository;
    @Mock PollGuestVoteRepository pollGuestVoteRepository;
    @Mock PhotoRepository photoRepository;
    @Mock PhotoStorage photoStorage;

    @InjectMocks GroupService groupService;

    // ---- helpers ----
    private void givenMember(boolean owner) {
        GroupMember gm = mock(GroupMember.class);
        when(gm.isOwner()).thenReturn(owner);
        when(memberRepository.findByGroupIdAndUserId(GROUP, A)).thenReturn(Optional.of(gm));
    }

    private static Event event(long id, long createdBy, Long fromPollId) {
        Event e = Event.builder()
                .title("t").startAt(Instant.now()).endAt(Instant.now().plusSeconds(3600))
                .createdBy(createdBy).fromPollId(fromPollId).build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static Poll poll(long id, long createdBy, Long linkedEventId) {
        Poll p = Poll.builder().groupId(GROUP).createdBy(createdBy).build();
        ReflectionTestUtils.setField(p, "id", id);
        if (linkedEventId != null) p.linkEventOnce(linkedEventId);
        return p;
    }

    private static EventGroup eg() { return mock(EventGroup.class); }

    // ============================================================

    @Test
    @DisplayName("단일그룹 origin 일정 → 일정 + 그림자 모두 삭제")
    void singleGroupOrigin_deletesEventAndShadow() {
        givenMember(false);
        Event origin = event(100, A, null);
        Event shadow = event(101, A, null);
        when(eventRepository.findOriginEventsByCreatedByAndGroupId(A, GROUP)).thenReturn(List.of(origin));
        when(eventRepository.findByShadowOfEventId(100L)).thenReturn(List.of(shadow));
        when(eventGroupRepository.findAllByEventId(100L)).thenReturn(List.of(eg())); // 단일그룹

        groupService.leave(A, GROUP);

        verify(eventRepository).delete(origin);
        verify(eventRepository).delete(shadow);
    }

    @Test
    @DisplayName("다중그룹(내캘린더+그룹) origin 일정 → 연결만 제거(원본 유지), 그림자는 cascade 삭제")
    void multiGroupOrigin_unlinksButCascadesShadow() {
        givenMember(false);
        Event origin = event(100, A, null);
        Event shadow = event(101, A, null);
        when(eventRepository.findOriginEventsByCreatedByAndGroupId(A, GROUP)).thenReturn(List.of(origin));
        when(eventRepository.findByShadowOfEventId(100L)).thenReturn(List.of(shadow));
        when(eventGroupRepository.findAllByEventId(100L)).thenReturn(List.of(eg(), eg())); // 다중그룹

        groupService.leave(A, GROUP);

        verify(eventRepository, never()).delete(origin);                 // 원본 유지
        verify(eventGroupRepository).deleteByEventIdAndGroupId(100L, GROUP); // 연결만 제거
        verify(eventRepository).delete(shadow);                          // 그림자는 삭제(의도)
    }

    @Test
    @DisplayName("방향1: 탈퇴자가 먼저 추가한 확정 일정이 삭제되면, 남의 폴의 linkedEventId 리셋(재등록 가능)")
    void deletedEvent_resetsOthersPollLinkedEvent() {
        givenMember(false);
        Event aEvent = event(200, A, 999L);                 // A 가 추가(createdBy=A), 출처는 남의 폴 999
        Poll othersPoll = poll(999, B, 200L);               // B 의 폴, linkedEventId=200(=aEvent)
        othersPoll.confirm(55L);                            // 확정된 폴(슬롯 55)
        when(eventRepository.findOriginEventsByCreatedByAndGroupId(A, GROUP)).thenReturn(List.of(aEvent));
        when(eventGroupRepository.findAllByEventId(200L)).thenReturn(List.of(eg())); // 단일 → 삭제됨
        when(pollRepository.findByLinkedEventIdIn(List.of(200L))).thenReturn(List.of(othersPoll));

        groupService.leave(A, GROUP);

        verify(eventRepository).delete(aEvent);                    // A 일정 삭제
        assertThat(othersPoll.getLinkedEventId()).isNull();        // 링크만 끊겨 재등록 가능
        assertThat(othersPoll.getConfirmedSlotId()).isEqualTo(55L); // 확정 자체는 유지
    }

    @Test
    @DisplayName("방향2: 탈퇴자 폴 삭제 시, 남이 추가한 파생 일정은 유지 + fromPollId만 끊김")
    void deletedPoll_detachesSurvivingEventFromPoll() {
        givenMember(false);
        Poll aPoll = poll(300, A, null);                    // A 가 만든 폴
        Event bEvent = event(301, B, 300L);                 // B 가 추가(createdBy=B), 출처 polll 300
        when(pollRepository.findByCreatedByAndGroupId(A, GROUP)).thenReturn(List.of(aPoll));
        when(eventRepository.findByFromPollIdIn(List.of(300L))).thenReturn(List.of(bEvent));

        groupService.leave(A, GROUP);

        verify(pollRepository).delete(aPoll);                 // A 폴 삭제
        verify(eventRepository, never()).delete(bEvent);      // B 일정은 유지
        assertThat(bEvent.getFromPollId()).isNull();          // 죽은 폴 참조만 끊김
    }

    @Test
    @DisplayName("탈퇴자가 만든 폴 → slots + votes 통째 삭제")
    void deletesOwnPollWithSlotsAndVotes() {
        givenMember(false);
        Poll aPoll = poll(300, A, null);
        when(pollRepository.findByCreatedByAndGroupId(A, GROUP)).thenReturn(List.of(aPoll));

        groupService.leave(A, GROUP);

        verify(pollVoteRepository).deleteAllByPollId(300L);
        verify(pollSlotRepository).deleteAllByPollId(300L);
        verify(pollRepository).delete(aPoll);
    }

    @Test
    @DisplayName("탈퇴자가 남의 폴에 한 투표 기록 삭제")
    void deletesOwnVotesOnOthersPolls() {
        givenMember(false);
        Poll othersPoll = poll(400, B, null);
        when(pollRepository.findAllByGroupId(GROUP)).thenReturn(List.of(othersPoll));

        groupService.leave(A, GROUP);

        verify(pollVoteRepository).deleteByPollIdAndUserId(400L, A);
    }

    @Test
    @DisplayName("탈퇴자가 올린 사진 → DB 삭제")
    void deletesOwnPhotos() {
        givenMember(false);
        Photo photo = mock(Photo.class);
        when(photo.getStorageKey()).thenReturn("k1");
        when(photoRepository.findAllByUploadedByAndGroupId(A, GROUP)).thenReturn(List.of(photo));

        groupService.leave(A, GROUP);

        verify(photoRepository).delete(photo);
    }

    @Test
    @DisplayName("그룹장(owner)은 탈퇴 불가")
    void ownerCannotLeave() {
        givenMember(true);
        assertThatThrownBy(() -> groupService.leave(A, GROUP))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNER_CANNOT_LEAVE);
        verify(memberRepository, never()).delete(any());
    }

    @Test
    @DisplayName("멤버가 아니면 탈퇴 불가")
    void nonMemberCannotLeave() {
        when(memberRepository.findByGroupIdAndUserId(GROUP, A)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> groupService.leave(A, GROUP))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("정상 탈퇴 시 멤버십 삭제")
    void leave_removesMembershipAndAudits() {
        givenMember(false);
        GroupMember gm = memberRepository.findByGroupIdAndUserId(GROUP, A).orElseThrow();

        groupService.leave(A, GROUP);

        verify(memberRepository).delete(gm);
    }
}

package com.gatherit.api.poll.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.poll.dto.PollShareResponse;
import com.gatherit.api.poll.dto.LinkedPollClaimRequest;
import com.gatherit.api.poll.dto.LinkedPollCodeClaimRequest;
import com.gatherit.api.poll.dto.PublicPollHandoffRequest;
import com.gatherit.api.poll.dto.PublicPollResponse;
import com.gatherit.api.poll.dto.PublicPollVoteRequest;
import com.gatherit.api.poll.entity.*;
import com.gatherit.api.poll.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicPollServiceTest {

    private static final long POLL_ID = 1L;
    private static final long GROUP_ID = 10L;
    private static final long SLOT_ID = 100L;
    private static final long PROPOSER = 7L;
    private static final String SHARE_TOKEN = "0123456789abcdef0123456789abcdef";

    @Mock PollRepository pollRepository;
    @Mock PollSlotRepository slotRepository;
    @Mock PollVoteRepository memberVoteRepository;
    @Mock PollGuestParticipantRepository participantRepository;
    @Mock PollGuestVoteRepository guestVoteRepository;
    @Mock GroupRepository groupRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupService groupService;

    PublicPollService service;

    @BeforeEach
    void setUp() {
        service = new PublicPollService(
                pollRepository, slotRepository, memberVoteRepository,
                participantRepository, guestVoteRepository, groupRepository,
                groupMemberRepository, groupService);
        ReflectionTestUtils.setField(service, "publicWebBaseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(service, "appDeepLinkBase", "gatherit://public-poll");
    }

    @Test
    @DisplayName("공개 링크 발급 — 발의자에게 32자 토큰 URL을 재사용 가능하게 반환")
    void issueShare_happy() {
        Poll poll = poll(false);
        when(pollRepository.findById(POLL_ID)).thenReturn(Optional.of(poll));
        when(pollRepository.existsByPublicShareToken(anyString())).thenReturn(false);

        PollShareResponse response = service.issueShare(PROPOSER, POLL_ID);

        assertThat(response.token()).matches("^[a-f0-9]{32}$");
        assertThat(response.url()).isEqualTo("http://localhost:3000/p/" + response.token());
        assertThat(response.expiresAt()).isAfter(poll.getDeadlineAt());
    }

    @Test
    @DisplayName("공개 상세 — 회원과 웹 참여자의 응답 수를 합산")
    void publicDetail_combinesMemberAndGuestVotes() {
        Poll poll = poll(true);
        PollSlot slot = slot();
        PollVote memberVote = PollVote.builder()
                .pollId(POLL_ID).slotId(SLOT_ID).userId(99L).available(true).build();
        PollGuestParticipant guest = guest(poll, "민지");
        PollGuestVote guestVote = PollGuestVote.builder().participant(guest).slotId(SLOT_ID).build();

        stubPublicRead(poll, slot);
        when(memberVoteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of(memberVote));
        when(participantRepository.findAllByPollId(POLL_ID)).thenReturn(List.of(guest));
        when(guestVoteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of(guestVote));

        PublicPollResponse response = service.getPublicPoll(SHARE_TOKEN, null);

        assertThat(response.totalRespondents()).isEqualTo(2);
        assertThat(response.memberRespondents()).isEqualTo(1);
        assertThat(response.guestRespondentNames()).containsExactly("민지");
        assertThat(response.slots().get(0).availableCount()).isEqualTo(2);
        assertThat(response.slots().get(0).availableGuestNames()).containsExactly("민지");
    }

    @Test
    @DisplayName("게스트 투표 — 닉네임 세션 생성 후 선택 슬롯 저장")
    void guestVote_createsParticipantAndVotes() {
        Poll poll = poll(true);
        PollSlot slot = slot();
        stubPublicRead(poll, slot);
        when(participantRepository.existsNickname(POLL_ID, "민지")).thenReturn(false);
        doAnswer(invocation -> {
            PollGuestParticipant participant = invocation.getArgument(0);
            ReflectionTestUtils.setField(participant, "id", 500L);
            return participant;
        }).when(participantRepository).saveAndFlush(any(PollGuestParticipant.class));

        var response = service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("  민지  ", null, Set.of(SLOT_ID)));

        assertThat(response.participantToken()).matches("^[a-f0-9]{32}$");
        verify(guestVoteRepository).deleteAllByParticipantId(500L);
        verify(guestVoteRepository).saveAll(argThat(votes -> votes.iterator().hasNext()));
    }

    @Test
    @DisplayName("게스트 투표 — 같은 닉네임의 새 브라우저 참여 차단")
    void guestVote_duplicateNickname_throws() {
        Poll poll = poll(true);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.existsNickname(POLL_ID, "민지")).thenReturn(true);

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", null, Set.of())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_GUEST_NAME_TAKEN);
    }

    @Test
    @DisplayName("웹 응답 세션으로 24시간 이내 앱 연결 딥링크를 발급한다")
    void issueHandoff_createsOneTimeAppLink() {
        Poll poll = poll(true);
        PollGuestParticipant guest = guest(poll, "민지");
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndEditTokenHash(eq(POLL_ID), anyString()))
                .thenReturn(Optional.of(guest));

        var response = service.issueHandoff(
                SHARE_TOKEN, new PublicPollHandoffRequest("a".repeat(32)));

        assertThat(response.appUrl()).matches(
                "^gatherit://public-poll\\?token=" + SHARE_TOKEN + "&handoff=[A-HJ-KM-NP-Z2-9]{8}$");
        assertThat(response.connectionCode()).matches("^[A-HJ-KM-NP-Z2-9]{4}-[A-HJ-KM-NP-Z2-9]{4}$");
        assertThat(response.expiresAt()).isAfter(Instant.now());
        assertThat(guest.getHandoffCodeHash()).hasSize(64);
    }

    @Test
    @DisplayName("앱 연결 코드를 현재 계정에 연결하고 같은 투표를 반환한다")
    void claimLinkedPoll_linksGuestResponseToAccount() throws Exception {
        Poll poll = poll(true);
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        String rawCode = "b".repeat(32);
        guest.issueHandoff(hash(rawCode), Instant.now().plusSeconds(3600));
        stubPublicRead(poll, slot);
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 44L))
                .thenReturn(Optional.empty());
        when(participantRepository.findByPollIdAndHandoffCodeHash(POLL_ID, hash(rawCode)))
                .thenReturn(Optional.of(guest));
        when(participantRepository.saveAndFlush(guest)).thenReturn(guest);

        PublicPollResponse response = service.claimLinkedPoll(
                44L, SHARE_TOKEN, new LinkedPollClaimRequest(rawCode));

        assertThat(guest.getLinkedUserId()).isEqualTo(44L);
        assertThat(guest.getHandoffCodeHash()).isEqualTo(hash(rawCode));
        assertThat(response.pollId()).isEqualTo(POLL_ID);
        assertThat(response.shareToken()).isEqualTo(SHARE_TOKEN);
        assertThat(response.myNickname()).isEqualTo("민지");
    }

    @Test
    @DisplayName("공유 토큰 없이 사람이 입력한 연결 코드로 웹 응답을 찾는다")
    void claimLinkedPollByCode_findsGuestGlobally() throws Exception {
        Poll poll = poll(true);
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        String rawCode = "ABCD2345";
        guest.issueHandoff(hash(rawCode), Instant.now().plusSeconds(3600));
        stubResponseRead(poll, slot);
        when(participantRepository.findByHandoffCodeHash(hash(rawCode))).thenReturn(Optional.of(guest));
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 55L)).thenReturn(Optional.empty());
        when(participantRepository.saveAndFlush(guest)).thenReturn(guest);

        PublicPollResponse response = service.claimLinkedPollByCode(
                55L, new LinkedPollCodeClaimRequest("ABCD-2345"));

        assertThat(guest.getLinkedUserId()).isEqualTo(55L);
        assertThat(response.shareToken()).isEqualTo(SHARE_TOKEN);
        assertThat(response.myNickname()).isEqualTo("민지");
    }

    // ── 엣지케이스: 토큰/공유 유효성 ─────────────────────────────────────────

    @Test
    @DisplayName("잘못된 형식의 토큰 — 32-hex 아니면 DB 조회 전에 P010")
    void resolveShare_invalidFormat_throws() {
        assertThatThrownBy(() -> service.getPublicPoll("not-a-valid-token", null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_SHARE_INVALID);
        verifyNoInteractions(pollRepository);
    }

    @Test
    @DisplayName("존재하지 않는 토큰 — P010")
    void resolveShare_notFound_throws() {
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublicPoll(SHARE_TOKEN, null))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_SHARE_INVALID);
    }

    @Test
    @DisplayName("만료된 공유 — P010")
    void resolveShare_expired_throws() {
        Poll poll = poll(false);
        poll.enablePublicShare(SHARE_TOKEN, Instant.parse("2000-01-01T00:00:00Z"));
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        assertThatThrownBy(() -> service.getPublicPoll(SHARE_TOKEN, null))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_SHARE_INVALID);
    }

    @Test
    @DisplayName("없는 토큰으로 투표 시도 — P010(500 아님)")
    void vote_shareNotFound_throws() {
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", null, Set.of())))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_SHARE_INVALID);
    }

    // ── 엣지케이스: 투표 상태 가드 ────────────────────────────────────────────

    @Test
    @DisplayName("마감 지난 투표 — 게스트 투표 차단")
    void vote_afterDeadline_throws() {
        Poll poll = Poll.builder().groupId(GROUP_ID).title("t")
                .deadlineAt(Instant.parse("2000-01-01T00:00:00Z"))
                .confirmPolicy(ConfirmPolicy.PROPOSER_ONLY).createdBy(PROPOSER).build();
        ReflectionTestUtils.setField(poll, "id", POLL_ID);
        poll.enablePublicShare(SHARE_TOKEN, Instant.parse("2100-01-01T00:00:00Z"));
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", null, Set.of())))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_DEADLINE_PASSED);
    }

    @Test
    @DisplayName("이미 확정된 투표 — 게스트 투표 차단")
    void vote_afterConfirmed_throws() {
        Poll poll = poll(true);
        ReflectionTestUtils.setField(poll, "confirmedSlotId", SLOT_ID);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", null, Set.of())))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_ALREADY_CONFIRMED);
    }

    @Test
    @DisplayName("존재하지 않는 슬롯 선택 — P (슬롯 검증)")
    void vote_invalidSlot_throws() {
        Poll poll = poll(true);
        PollSlot slot = slot();
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.existsNickname(POLL_ID, "민지")).thenReturn(false);
        doAnswer(inv -> {
            PollGuestParticipant p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 500L);
            return p;
        }).when(participantRepository).saveAndFlush(any(PollGuestParticipant.class));
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slot));

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", null, Set.of(999L))))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_SLOT_NOT_FOUND);
    }

    // ── 엣지케이스: 게스트 세션/편집 ─────────────────────────────────────────

    @Test
    @DisplayName("참여 토큰으로 기존 응답 재편집 — 이전 선택 삭제 후 재저장")
    void vote_editExistingSession_replacesVotes() {
        Poll poll = poll(true);
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        String editToken = "e".repeat(32);
        stubPublicRead(poll, slot);
        when(participantRepository.findByPollIdAndEditTokenHash(eq(POLL_ID), anyString()))
                .thenReturn(Optional.of(guest));

        var response = service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", editToken, Set.of(SLOT_ID)));

        assertThat(response.participantToken()).isEqualTo(editToken);
        verify(guestVoteRepository).deleteAllByParticipantId(500L);
        verify(guestVoteRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("유효하지 않은 참여 토큰 — 세션 오류")
    void vote_invalidSession_throws() {
        Poll poll = poll(true);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndEditTokenHash(eq(POLL_ID), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.vote(SHARE_TOKEN,
                new PublicPollVoteRequest("민지", "z".repeat(32), Set.of())))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_GUEST_SESSION_INVALID);
    }

    // ── 엣지케이스: claim 보안(탈취/멱등/만료) ───────────────────────────────

    @Test
    @DisplayName("[보안] 이미 다른 계정에 연결된 응답 — 탈취 차단")
    void claim_alreadyLinkedToAnotherUser_throws() throws Exception {
        Poll poll = poll(true);
        PollGuestParticipant guest = guest(poll, "민지");
        String rawCode = "ABCD2345";
        guest.issueHandoff(hash(rawCode), Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(guest, "linkedUserId", 44L);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 55L)).thenReturn(Optional.empty());
        when(participantRepository.findByPollIdAndHandoffCodeHash(POLL_ID, hash(rawCode)))
                .thenReturn(Optional.of(guest));

        assertThatThrownBy(() -> service.claimLinkedPoll(
                55L, SHARE_TOKEN, new LinkedPollClaimRequest(rawCode)))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_GUEST_ALREADY_LINKED);
        verify(participantRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("같은 계정 재연결 — 멱등(오류 없이 응답 반환)")
    void claim_sameUser_idempotent() {
        Poll poll = poll(true);
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        ReflectionTestUtils.setField(guest, "linkedUserId", 44L);
        stubPublicRead(poll, slot);
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 44L)).thenReturn(Optional.of(guest));

        PublicPollResponse response = service.claimLinkedPoll(
                44L, SHARE_TOKEN, new LinkedPollClaimRequest("ABCD2345"));

        assertThat(response.pollId()).isEqualTo(POLL_ID);
        assertThat(response.myNickname()).isEqualTo("민지");
        verify(participantRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("만료된 핸드오프 코드 — 연결 차단")
    void claim_expiredHandoff_throws() throws Exception {
        Poll poll = poll(true);
        PollGuestParticipant guest = guest(poll, "민지");
        String rawCode = "ABCD2345";
        guest.issueHandoff(hash(rawCode), Instant.parse("2000-01-01T00:00:00Z"));
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 55L)).thenReturn(Optional.empty());
        when(participantRepository.findByPollIdAndHandoffCodeHash(POLL_ID, hash(rawCode)))
                .thenReturn(Optional.of(guest));

        assertThatThrownBy(() -> service.claimLinkedPoll(
                55L, SHARE_TOKEN, new LinkedPollClaimRequest(rawCode)))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_HANDOFF_INVALID);
    }

    // ── 엣지케이스: linked 접근 제어 ─────────────────────────────────────────

    @Test
    @DisplayName("연결하지 않은 유저의 linked 상세 접근 — 거부")
    void getLinkedPoll_notParticipant_throws() {
        Poll poll = poll(true);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLinkedPoll(99L, SHARE_TOKEN))
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.POLL_LINKED_ACCESS_DENIED);
    }

    // ── 엣지케이스: 공유 링크 만료 후 연결(claim)된 투표 보존 (옵션1) ──────────

    @Test
    @DisplayName("[옵션1] 공유 링크 만료돼도 연결된 투표는 목록에 유지")
    void findLinkedPolls_includesExpiredShare() {
        Poll poll = poll(false);
        poll.enablePublicShare(SHARE_TOKEN, Instant.parse("2000-01-01T00:00:00Z")); // 만료됨
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        ReflectionTestUtils.setField(guest, "linkedUserId", 44L);
        when(participantRepository.findAllByLinkedUserId(44L)).thenReturn(List.of(guest));
        stubResponseRead(poll, slot);

        List<PublicPollResponse> res = service.findLinkedPolls(44L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).pollId()).isEqualTo(POLL_ID);
    }

    @Test
    @DisplayName("[옵션1] 만료된 공유라도 연결된 유저는 linked 상세 접근 가능")
    void getLinkedPoll_expiredShare_stillAccessible() {
        Poll poll = poll(false);
        poll.enablePublicShare(SHARE_TOKEN, Instant.parse("2000-01-01T00:00:00Z")); // 만료됨
        PollSlot slot = slot();
        PollGuestParticipant guest = guest(poll, "민지");
        ReflectionTestUtils.setField(guest, "linkedUserId", 44L);
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        when(participantRepository.findByPollIdAndLinkedUserId(POLL_ID, 44L)).thenReturn(Optional.of(guest));
        stubResponseRead(poll, slot);

        PublicPollResponse res = service.getLinkedPoll(44L, SHARE_TOKEN);

        assertThat(res.pollId()).isEqualTo(POLL_ID);
        assertThat(res.myNickname()).isEqualTo("민지");
    }

    private void stubPublicRead(Poll poll, PollSlot slot) {
        when(pollRepository.findByPublicShareToken(SHARE_TOKEN)).thenReturn(Optional.of(poll));
        stubResponseRead(poll, slot);
    }

    private void stubResponseRead(Poll poll, PollSlot slot) {
        when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(group()));
        when(slotRepository.findAllByPollIdOrderByStartAtAsc(POLL_ID)).thenReturn(List.of(slot));
        when(memberVoteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
        when(participantRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
        when(guestVoteRepository.findAllByPollId(POLL_ID)).thenReturn(List.of());
    }

    private Poll poll(boolean shared) {
        Poll poll = Poll.builder()
                .groupId(GROUP_ID)
                .title("이번 주 언제 만날까?")
                .deadlineAt(Instant.parse("2099-12-30T15:00:00Z"))
                .confirmPolicy(ConfirmPolicy.PROPOSER_ONLY)
                .createdBy(PROPOSER)
                .build();
        ReflectionTestUtils.setField(poll, "id", POLL_ID);
        if (shared) poll.enablePublicShare(SHARE_TOKEN, Instant.parse("2100-01-06T15:00:00Z"));
        return poll;
    }

    private PollSlot slot() {
        PollSlot slot = PollSlot.builder()
                .pollId(POLL_ID)
                .startAt(Instant.parse("2099-12-31T10:00:00Z"))
                .endAt(Instant.parse("2099-12-31T11:00:00Z"))
                .build();
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        return slot;
    }

    private PollGuestParticipant guest(Poll poll, String nickname) {
        PollGuestParticipant participant = PollGuestParticipant.builder()
                .poll(poll).nickname(nickname).editTokenHash("a".repeat(64)).build();
        ReflectionTestUtils.setField(participant, "id", 500L);
        return participant;
    }

    private Group group() {
        Group group = Group.builder()
                .name("주말 맛집 원정대")
                .colorKey(GroupColor.team)
                .createdBy(PROPOSER)
                .personal(false)
                .build();
        ReflectionTestUtils.setField(group, "id", GROUP_ID);
        return group;
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

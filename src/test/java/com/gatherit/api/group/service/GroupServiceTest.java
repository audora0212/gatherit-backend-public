package com.gatherit.api.group.service;

import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.dto.GroupCreateRequest;
import com.gatherit.api.group.dto.GroupMemberResponse;
import com.gatherit.api.group.dto.GroupPreviewResponse;
import com.gatherit.api.group.dto.GroupResponse;
import com.gatherit.api.group.dto.GroupUpdateRequest;
import com.gatherit.api.group.dto.InviteResponse;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.GroupMemberPolicy;
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.group.entity.PhotoDeletePolicy;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollGuestParticipant;
import com.gatherit.api.poll.entity.PollGuestVote;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.PollVote;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.poll.repository.PollGuestVoteRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GroupService — leave/kick cleanup 외 나머지 메서드 단위 테스트.
 * leave/kick cleanup 은 GroupServiceLeaveCleanupTest 가 커버하므로 여기서는 제외.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupServiceTest {

    private static final long USER_ID  = 1L;
    private static final long OTHER_ID = 2L;
    private static final long GROUP_ID = 10L;

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

    // ─── 헬퍼 ───────────────────────────────────────────────────────────────

    private static Group group(long id, long ownerId, boolean personal) {
        Group g = Group.builder()
                .name("테스트그룹")
                .colorKey(GroupColor.team)
                .createdBy(ownerId)
                .personal(personal)
                .build();
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    private static Group group(long id, long ownerId) {
        return group(id, ownerId, false);
    }

    private static GroupMember member(long groupId, long userId, MemberRole role, GroupColor color) {
        GroupMember gm = GroupMember.builder()
                .groupId(groupId).userId(userId).role(role).colorKey(color).build();
        return gm;
    }

    private static User user(long id, String nickname) {
        User u = User.builder().nickname(nickname).email("test@test.com").build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    /** 요청자가 해당 그룹의 멤버임을 stubbing. */
    private void givenIsMember(long userId, long groupId) {
        when(memberRepository.existsByGroupIdAndUserId(groupId, userId)).thenReturn(true);
    }

    /** groupRepository.findById stubbing. */
    private void givenGroupExists(Group g) {
        when(groupRepository.findById(g.getId())).thenReturn(Optional.of(g));
    }

    // ════════════════════════════════════════════════════════════════════════
    // create
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("create — 그룹 생성")
    class CreateTests {

        @Test
        @DisplayName("정상 생성 — 그룹/멤버십 저장 + GroupResponse 반환")
        void create_savesGroupAndOwnerMembership() {
            GroupCreateRequest req = new GroupCreateRequest("모임", GroupColor.pink);
            when(memberRepository.countByGroupId(any())).thenReturn(1L);

            GroupResponse res = groupService.create(USER_ID, req);

            verify(groupRepository).save(any(Group.class));
            verify(memberRepository).save(any(GroupMember.class));
            assertThat(res.name()).isEqualTo("모임");
            assertThat(res.colorKey()).isEqualTo(GroupColor.pink);
            assertThat(res.publicPollJoinAllowed()).isTrue();
        }

        @Test
        @DisplayName("생성자가 선택한 색이 본인 멤버 colorKey 에 그대로 설정된다")
        void create_usesRequestedColorForOwnerMember() {
            GroupCreateRequest req = new GroupCreateRequest("모임", GroupColor.family);
            when(memberRepository.countByGroupId(any())).thenReturn(1L);

            GroupResponse res = groupService.create(USER_ID, req);

            assertThat(res.colorKey()).isEqualTo(GroupColor.family);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // createPersonalGroup
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createPersonalGroup — 개인 캘린더 자동 생성")
    class CreatePersonalGroupTests {

        @Test
        @DisplayName("personal=true 그룹이 저장되고 반환 Group 은 personal=true")
        void createPersonalGroup_returnsPersonalGroup() {
            Group result = groupService.createPersonalGroup(USER_ID);

            verify(groupRepository).save(any(Group.class));
            verify(memberRepository).save(any(GroupMember.class));
            assertThat(result.isPersonal()).isTrue();
            assertThat(result.getName()).isEqualTo("내 캘린더");
        }

        @Test
        @DisplayName("소유자 멤버십은 OWNER 역할로 저장된다")
        void createPersonalGroup_savesOwnerMembership() {
            groupService.createPersonalGroup(USER_ID);

            ArgumentCaptor<GroupMember> captor = ArgumentCaptor.forClass(GroupMember.class);
            verify(memberRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(MemberRole.OWNER);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // update
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("update — 그룹 정보 수정")
    class UpdateTests {

        @Test
        @DisplayName("OWNER 가 이름 변경 → GroupResponse 반환")
        void update_ownerRenamesGroup() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            GroupUpdateRequest req = new GroupUpdateRequest("새이름", null, null, null, null, null, null);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.team)));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(3L);

            GroupResponse res = groupService.update(USER_ID, GROUP_ID, req);

            assertThat(res.name()).isEqualTo("새이름");
            assertThat(res.memberCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("OWNER 가 아니면 NOT_GROUP_OWNER 예외")
        void update_nonOwnerThrows() {
            Group g = group(GROUP_ID, OTHER_ID); // owner=OTHER_ID, 요청자=USER_ID
            givenGroupExists(g);
            GroupUpdateRequest req = new GroupUpdateRequest("이름", null, null, null, null, null, null);

            assertThatThrownBy(() -> groupService.update(USER_ID, GROUP_ID, req))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_OWNER);
        }

        @Test
        @DisplayName("그룹이 없으면 GROUP_NOT_FOUND 예외")
        void update_groupNotFound() {
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());
            GroupUpdateRequest req = new GroupUpdateRequest("이름", null, null, null, null, null, null);

            assertThatThrownBy(() -> groupService.update(USER_ID, GROUP_ID, req))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("invitePolicy 필드만 변경 → photoDeletePolicy 는 기존 유지")
        void update_partialUpdate_onlyInvitePolicy() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            GroupUpdateRequest req = new GroupUpdateRequest(null, null, null, GroupMemberPolicy.OWNER_ONLY, null, null, null);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.team)));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(1L);

            GroupResponse res = groupService.update(USER_ID, GROUP_ID, req);

            assertThat(res.invitePolicy()).isEqualTo(GroupMemberPolicy.OWNER_ONLY);
        }

        @Test
        @DisplayName("웹 투표 참여자 직접 가입을 끌 수 있다")
        void update_disablesPublicPollJoin() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            GroupUpdateRequest req = new GroupUpdateRequest(
                    null, null, null, null, null, null, false);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID))
                    .thenReturn(Optional.of(member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.team)));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(1L);

            GroupResponse res = groupService.update(USER_ID, GROUP_ID, req);

            assertThat(res.publicPollJoinAllowed()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // issueInvite
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("issueInvite — 초대 토큰 발급")
    class IssueInviteTests {

        @Test
        @DisplayName("일반 그룹 멤버 → 토큰 발급 성공")
        void issueInvite_memberGetsToken() {
            Group g = group(GROUP_ID, OTHER_ID); // owner=OTHER, 요청자=USER_ID
            givenGroupExists(g);
            givenIsMember(USER_ID, GROUP_ID);
            InviteTokenStore.Issued issued = new InviteTokenStore.Issued("ABC12345", Instant.now().plusSeconds(3600));
            when(inviteTokenStore.issue(GROUP_ID)).thenReturn(issued);

            InviteResponse res = groupService.issueInvite(USER_ID, GROUP_ID);

            assertThat(res.token()).isEqualTo("ABC12345");
        }

        @Test
        @DisplayName("개인 캘린더는 초대 불가 — PERSONAL_GROUP_NOT_INVITABLE")
        void issueInvite_personalGroupThrows() {
            Group g = group(GROUP_ID, USER_ID, true);
            givenGroupExists(g);
            givenIsMember(USER_ID, GROUP_ID);

            assertThatThrownBy(() -> groupService.issueInvite(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERSONAL_GROUP_NOT_INVITABLE);
        }

        @Test
        @DisplayName("invitePolicy=OWNER_ONLY 일 때 일반 멤버는 GROUP_INVITE_FORBIDDEN")
        void issueInvite_ownerOnlyPolicyForbidsMember() {
            Group g = Group.builder()
                    .name("그룹").colorKey(GroupColor.team).createdBy(OTHER_ID).personal(false)
                    .invitePolicy(GroupMemberPolicy.OWNER_ONLY)
                    .build();
            ReflectionTestUtils.setField(g, "id", GROUP_ID);
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            givenIsMember(USER_ID, GROUP_ID);

            assertThatThrownBy(() -> groupService.issueInvite(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GROUP_INVITE_FORBIDDEN);
        }

        @Test
        @DisplayName("invitePolicy=OWNER_ONLY 일 때 OWNER 는 토큰 발급 성공")
        void issueInvite_ownerPassesOwnerOnlyPolicy() {
            Group g = Group.builder()
                    .name("그룹").colorKey(GroupColor.team).createdBy(USER_ID).personal(false)
                    .invitePolicy(GroupMemberPolicy.OWNER_ONLY)
                    .build();
            ReflectionTestUtils.setField(g, "id", GROUP_ID);
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            givenIsMember(USER_ID, GROUP_ID);
            InviteTokenStore.Issued issued = new InviteTokenStore.Issued("XYZ99999", Instant.now().plusSeconds(3600));
            when(inviteTokenStore.issue(GROUP_ID)).thenReturn(issued);

            InviteResponse res = groupService.issueInvite(USER_ID, GROUP_ID);

            assertThat(res.token()).isEqualTo("XYZ99999");
        }

        @Test
        @DisplayName("멤버가 아니면 NOT_GROUP_MEMBER 예외")
        void issueInvite_nonMemberThrows() {
            Group g = group(GROUP_ID, OTHER_ID);
            givenGroupExists(g);
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> groupService.issueInvite(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_MEMBER);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // previewByToken
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("previewByToken — 초대 코드 미리보기")
    class PreviewByTokenTests {

        @Test
        @DisplayName("유효한 토큰 → 그룹 정보 + alreadyMember=false 반환")
        void previewByToken_validToken_notMember() {
            Group g = group(GROUP_ID, OTHER_ID);
            when(inviteTokenStore.validate("TOKEN1")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(5L);
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);

            GroupPreviewResponse res = groupService.previewByToken(USER_ID, "TOKEN1");

            assertThat(res.id()).isEqualTo(GROUP_ID);
            assertThat(res.memberCount()).isEqualTo(5L);
            assertThat(res.alreadyMember()).isFalse();
        }

        @Test
        @DisplayName("이미 멤버이면 alreadyMember=true")
        void previewByToken_alreadyMember() {
            Group g = group(GROUP_ID, OTHER_ID);
            when(inviteTokenStore.validate("TOKEN2")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(true);

            GroupPreviewResponse res = groupService.previewByToken(USER_ID, "TOKEN2");

            assertThat(res.alreadyMember()).isTrue();
        }

        @Test
        @DisplayName("유효하지 않은 토큰 → INVITE_TOKEN_INVALID 예외")
        void previewByToken_invalidToken() {
            when(inviteTokenStore.validate("BAD")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.previewByToken(USER_ID, "BAD"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_TOKEN_INVALID);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // joinByToken
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("joinByToken — 초대 코드로 그룹 가입")
    class JoinByTokenTests {

        @Test
        @DisplayName("유효한 토큰 + 미가입자 → 멤버십 저장 + GroupResponse 반환")
        void joinByToken_savesNewMembership() {
            Group g = group(GROUP_ID, OTHER_ID);
            when(inviteTokenStore.validate("JOINTOKEN")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);
            when(memberRepository.findAllByUserId(USER_ID)).thenReturn(List.of()); // 기존 색 없음 → pink
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "철수")));

            GroupResponse res = groupService.joinByToken(USER_ID, "JOINTOKEN");

            verify(memberRepository).save(any(GroupMember.class));
            assertThat(res.memberCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("이미 멤버이면 ALREADY_GROUP_MEMBER 예외")
        void joinByToken_alreadyMemberThrows() {
            Group g = group(GROUP_ID, OTHER_ID);
            when(inviteTokenStore.validate("DUP")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> groupService.joinByToken(USER_ID, "DUP"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_GROUP_MEMBER);
        }

        @Test
        @DisplayName("유효하지 않은 토큰 → INVITE_TOKEN_INVALID 예외")
        void joinByToken_invalidToken() {
            when(inviteTokenStore.validate("EXPIRED")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.joinByToken(USER_ID, "EXPIRED"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVITE_TOKEN_INVALID);
        }

        @Test
        @DisplayName("가입자가 owner 가 아닐 때 owner 에게 GROUP_JOINED_BY 알림 전송")
        void joinByToken_notifiesOwner() {
            Group g = group(GROUP_ID, OTHER_ID); // owner = OTHER_ID
            when(inviteTokenStore.validate("T1")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);
            when(memberRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "영희")));

            groupService.joinByToken(USER_ID, "T1");

            verify(notificationService).pushScoped(eq(OTHER_ID), any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("owner 가 본인 토큰으로 가입 시도 시 알림 미전송(이미 멤버여서 예외 발생)")
        void joinByToken_ownerJoinSelf_throws() {
            Group g = group(GROUP_ID, USER_ID); // owner = USER_ID
            when(inviteTokenStore.validate("T2")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> groupService.joinByToken(USER_ID, "T2"))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_GROUP_MEMBER);
            verify(notificationService, never()).pushScoped(any(), any(), any(), any(), any(), anyList());
        }

        @Test
        @DisplayName("가입 시 pickUnusedColor — 기존 색 없으면 첫 번째 enum(pink) 선택")
        void joinByToken_picksFirstUnusedColor() {
            Group g = group(GROUP_ID, OTHER_ID);
            when(inviteTokenStore.validate("T3")).thenReturn(Optional.of(GROUP_ID));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);
            when(memberRepository.findAllByUserId(USER_ID)).thenReturn(List.of()); // 기존 색 없음
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID, "준호")));

            GroupResponse res = groupService.joinByToken(USER_ID, "T3");

            assertThat(res.colorKey()).isEqualTo(GroupColor.pink); // 첫 번째 enum
        }
    }

    @Nested
    @DisplayName("joinFromLinkedPoll — 연결한 웹 투표에서 직접 가입")
    class JoinFromLinkedPollTests {

        @Test
        @DisplayName("기본 허용 그룹이면 가입하고 게스트 응답을 멤버 응답으로 병합")
        void joinsAndMigratesGuestVote() {
            Group g = group(GROUP_ID, OTHER_ID);
            PollGuestParticipant participant = mock(PollGuestParticipant.class);
            PollGuestVote guestVote = mock(PollGuestVote.class);
            PollSlot slot = mock(PollSlot.class);
            when(participant.getId()).thenReturn(91L);
            when(participant.getPollId()).thenReturn(77L);
            when(guestVote.getSlotId()).thenReturn(88L);
            when(slot.getId()).thenReturn(88L);
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.empty());
            when(memberRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(2L);
            when(pollGuestParticipantRepository.findAllLinkedByUserIdAndGroupId(USER_ID, GROUP_ID))
                    .thenReturn(List.of(participant));
            when(pollGuestVoteRepository.findAllByParticipantId(91L)).thenReturn(List.of(guestVote));
            when(pollSlotRepository.findAllByPollIdOrderByStartAtAsc(77L)).thenReturn(List.of(slot));
            when(pollVoteRepository.findById(any(PollVote.PK.class))).thenReturn(Optional.empty());

            GroupResponse res = groupService.joinFromLinkedPoll(USER_ID, GROUP_ID);

            assertThat(res.publicPollJoinAllowed()).isTrue();
            ArgumentCaptor<PollVote> voteCaptor = ArgumentCaptor.forClass(PollVote.class);
            verify(pollVoteRepository).save(voteCaptor.capture());
            assertThat(voteCaptor.getValue().isAvailable()).isTrue();
            assertThat(voteCaptor.getValue().getUserId()).isEqualTo(USER_ID);
            verify(pollGuestVoteRepository).deleteAllByParticipantId(91L);
            verify(pollGuestParticipantRepository).delete(participant);
        }

        @Test
        @DisplayName("직접 가입이 꺼진 그룹이면 그룹장 초대 필요 오류")
        void closedGroupRequiresInvite() {
            Group g = group(GROUP_ID, OTHER_ID);
            g.setPublicPollJoinAllowed(false);
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.joinFromLinkedPoll(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue(
                            "errorCode", ErrorCode.GROUP_PUBLIC_POLL_JOIN_FORBIDDEN);
            verify(memberRepository, never()).save(any(GroupMember.class));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // transferOwner
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("transferOwner — 그룹장 양도")
    class TransferOwnerTests {

        @Test
        @DisplayName("OWNER 가 다른 멤버에게 양도 → createdBy + role 교체")
        void transferOwner_changesOwnership() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            GroupMember targetGm = member(GROUP_ID, OTHER_ID, MemberRole.MEMBER, GroupColor.team);
            GroupMember currentGm = member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.pink);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, OTHER_ID)).thenReturn(Optional.of(targetGm));
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.of(currentGm));

            groupService.transferOwner(USER_ID, GROUP_ID, OTHER_ID);

            assertThat(g.getCreatedBy()).isEqualTo(OTHER_ID);
            assertThat(targetGm.getRole()).isEqualTo(MemberRole.OWNER);
            assertThat(currentGm.getRole()).isEqualTo(MemberRole.MEMBER);
        }

        @Test
        @DisplayName("자기 자신에게 양도 → no-op (예외 없음)")
        void transferOwner_toSelf_isNoOp() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);

            groupService.transferOwner(USER_ID, GROUP_ID, USER_ID);

            assertThat(g.getCreatedBy()).isEqualTo(USER_ID); // 변경 없음
            verify(memberRepository, never()).findByGroupIdAndUserId(eq(GROUP_ID), eq(OTHER_ID));
        }

        @Test
        @DisplayName("대상자가 멤버가 아니면 NOT_GROUP_MEMBER 예외")
        void transferOwner_targetNotMemberThrows() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, OTHER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.transferOwner(USER_ID, GROUP_ID, OTHER_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_MEMBER);
        }

        @Test
        @DisplayName("요청자가 OWNER 가 아니면 NOT_GROUP_OWNER 예외")
        void transferOwner_nonOwnerThrows() {
            Group g = group(GROUP_ID, OTHER_ID); // owner=OTHER_ID
            givenGroupExists(g);

            assertThatThrownBy(() -> groupService.transferOwner(USER_ID, GROUP_ID, OTHER_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_OWNER);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // delete
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("delete — 그룹 삭제")
    class DeleteTests {

        @Test
        @DisplayName("OWNER 가 삭제 — 멤버 row + 그룹 row 삭제")
        void delete_ownerDeletesGroup() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(eventGroupRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(pollRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(photoRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());

            groupService.delete(USER_ID, GROUP_ID);

            verify(memberRepository).deleteByGroupId(GROUP_ID);
            verify(groupRepository).delete(g);
        }

        @Test
        @DisplayName("OWNER 가 아니면 NOT_GROUP_OWNER 예외")
        void delete_nonOwnerThrows() {
            Group g = group(GROUP_ID, OTHER_ID);
            givenGroupExists(g);

            assertThatThrownBy(() -> groupService.delete(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_OWNER);
        }

        @Test
        @DisplayName("그룹이 없으면 GROUP_NOT_FOUND 예외")
        void delete_groupNotFoundThrows() {
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.delete(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GROUP_NOT_FOUND);
        }

        @Test
        @DisplayName("멤버 전원에 대해 cleanupMemberData 로직 수행 (eventGroupRepository.deleteAllByGroupId 안전망 호출)")
        void delete_callsResidualEventGroupCleanup() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(eventGroupRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(pollRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(photoRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());

            groupService.delete(USER_ID, GROUP_ID);

            verify(eventGroupRepository).deleteAllByGroupId(GROUP_ID);
        }

        @Test
        @DisplayName("잔여 폴(다른 사람 소유) → slots + votes + poll 삭제")
        void delete_deletesResidualPolls() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(eventGroupRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            Poll residualPoll = mock(Poll.class);
            when(residualPoll.getId()).thenReturn(99L);
            when(pollRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of(residualPoll));
            when(photoRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());

            groupService.delete(USER_ID, GROUP_ID);

            verify(pollVoteRepository).deleteAllByPollId(99L);
            verify(pollSlotRepository).deleteAllByPollId(99L);
            verify(pollRepository).delete(residualPoll);
        }

        @Test
        @DisplayName("잔여 사진 → DB 삭제")
        void delete_deletesResidualPhotos() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(eventGroupRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(pollRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            Photo photo = mock(Photo.class);
            when(photo.getStorageKey()).thenReturn("key-abc");
            when(photoRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of(photo));

            groupService.delete(USER_ID, GROUP_ID);

            verify(photoRepository).delete(photo);
        }

        @Test
        @DisplayName("개인 그룹과 멤버십이 함께 삭제된다")
        void delete_personalRemovesGroupAndMembership() {
            Group g = group(GROUP_ID, USER_ID, true);
            givenGroupExists(g);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(eventGroupRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(pollRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());
            when(photoRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of());

            groupService.delete(USER_ID, GROUP_ID);

            verify(memberRepository).deleteByGroupId(GROUP_ID);
            verify(groupRepository).delete(g);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // listMembers
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("listMembers — 멤버 목록 조회")
    class ListMembersTests {

        @Test
        @DisplayName("멤버가 조회하면 닉네임/역할 포함된 목록 반환")
        void listMembers_returnsAllMembers() {
            Group g = group(GROUP_ID, USER_ID);
            givenGroupExists(g);
            givenIsMember(USER_ID, GROUP_ID);
            GroupMember gm1 = member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.pink);
            GroupMember gm2 = member(GROUP_ID, OTHER_ID, MemberRole.MEMBER, GroupColor.team);
            when(memberRepository.findAllByGroupId(GROUP_ID)).thenReturn(List.of(gm1, gm2));
            when(userRepository.findAllById(List.of(USER_ID, OTHER_ID))).thenReturn(
                    List.of(user(USER_ID, "철수"), user(OTHER_ID, "영희")));

            List<GroupMemberResponse> result = groupService.listMembers(USER_ID, GROUP_ID);

            assertThat(result).hasSize(2);
            assertThat(result.stream().map(GroupMemberResponse::nickname))
                    .containsExactlyInAnyOrder("철수", "영희");
        }

        @Test
        @DisplayName("멤버가 아니면 NOT_GROUP_MEMBER 예외")
        void listMembers_nonMemberThrows() {
            Group g = group(GROUP_ID, OTHER_ID);
            givenGroupExists(g);
            when(memberRepository.existsByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> groupService.listMembers(USER_ID, GROUP_ID))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_MEMBER);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // updateMyColor
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("updateMyColor — 본인 그룹 색 변경")
    class UpdateMyColorTests {

        @Test
        @DisplayName("멤버인 경우 본인 색 변경 + GroupResponse 반환")
        void updateMyColor_changesColor() {
            Group g = group(GROUP_ID, USER_ID);
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.of(g));
            GroupMember gm = member(GROUP_ID, USER_ID, MemberRole.OWNER, GroupColor.pink);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.of(gm));
            when(memberRepository.countByGroupId(GROUP_ID)).thenReturn(1L);

            GroupResponse res = groupService.updateMyColor(USER_ID, GROUP_ID, GroupColor.green);

            assertThat(gm.getColorKey()).isEqualTo(GroupColor.green);
            assertThat(res.colorKey()).isEqualTo(GroupColor.green);
        }

        @Test
        @DisplayName("멤버가 아니면 NOT_GROUP_MEMBER 예외")
        void updateMyColor_nonMemberThrows() {
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.updateMyColor(USER_ID, GROUP_ID, GroupColor.green))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_GROUP_MEMBER);
        }

        @Test
        @DisplayName("그룹이 없으면 GROUP_NOT_FOUND 예외")
        void updateMyColor_groupNotFoundThrows() {
            GroupMember gm = member(GROUP_ID, USER_ID, MemberRole.MEMBER, GroupColor.pink);
            when(memberRepository.findByGroupIdAndUserId(GROUP_ID, USER_ID)).thenReturn(Optional.of(gm));
            when(groupRepository.findById(GROUP_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> groupService.updateMyColor(USER_ID, GROUP_ID, GroupColor.club))
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GROUP_NOT_FOUND);
        }
    }
}

package com.gatherit.api.user.service;

import com.gatherit.api.auth.service.RefreshTokenStore;
import com.gatherit.api.event.repository.DdayPinRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.user.dto.MeStatsResponse;
import com.gatherit.api.user.dto.UpdateMeRequest;
import com.gatherit.api.user.dto.UserMeResponse;
import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.entity.UserRole;
import com.gatherit.api.user.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserService 단위 테스트 — 조회/수정/탈퇴 + 에러 분기 집중.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    private static final long USER_ID = 1L;

    @Mock UserRepository userRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository groupRepository;
    @Mock EventRepository eventRepository;
    @Mock PollVoteRepository pollVoteRepository;
    @Mock PollGuestParticipantRepository pollGuestParticipantRepository;
    @Mock DdayPinRepository ddayPinRepository;
    @Mock PhotoRepository photoRepository;
    @Mock PhotoStorage photoStorage;
    @Mock RefreshTokenStore refreshTokenStore;
    @Mock GroupService groupService;
    @Mock NotificationService notificationService;

    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(
                userRepository, groupMemberRepository, groupRepository,
                eventRepository, pollVoteRepository, pollGuestParticipantRepository, ddayPinRepository,
                photoRepository, photoStorage, refreshTokenStore,
                groupService, notificationService);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private User user(long id, String nickname, OauthProvider provider) {
        User u = User.builder()
                .nickname(nickname)
                .email("test@example.com")
                .oauthProvider(provider)
                .role(UserRole.USER)
                .build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private User user(long id) {
        return user(id, "테스터", OauthProvider.KAKAO);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ─── getMe ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMe — 존재하는 유저 조회 시 UserMeResponse 반환")
    void getMe_success() {
        User u = user(USER_ID, "테스터", OauthProvider.KAKAO);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        UserMeResponse res = service.getMe(USER_ID);

        assertThat(res.id()).isEqualTo(USER_ID);
        assertThat(res.nickname()).isEqualTo("테스터");
        assertThat(res.oauthProvider()).isEqualTo(OauthProvider.KAKAO);
    }

    @Test
    @DisplayName("getMe — 없는 유저 조회 시 USER_NOT_FOUND")
    void getMe_userNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMe(USER_ID))
                .isInstanceOf(CustomException.class)
                .extracting(UserServiceTest::codeOf)
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ─── getStats ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getStats — 각 카운트 집계 값 그대로 반환")
    void getStats_success() {
        when(groupMemberRepository.countByUserIdExcludingPersonal(USER_ID)).thenReturn(3L);
        when(photoRepository.countSharedPhotosForUser(USER_ID)).thenReturn(12L);
        when(eventRepository.countUpcomingForUser(eq(USER_ID), any(Instant.class))).thenReturn(5L);

        MeStatsResponse res = service.getStats(USER_ID);

        assertThat(res.groupCount()).isEqualTo(3L);
        assertThat(res.sharedPhotoCount()).isEqualTo(12L);
        assertThat(res.upcomingEventCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("getStats — 모두 0 이어도 정상 응답")
    void getStats_allZero() {
        when(groupMemberRepository.countByUserIdExcludingPersonal(USER_ID)).thenReturn(0L);
        when(photoRepository.countSharedPhotosForUser(USER_ID)).thenReturn(0L);
        when(eventRepository.countUpcomingForUser(eq(USER_ID), any(Instant.class))).thenReturn(0L);

        MeStatsResponse res = service.getStats(USER_ID);

        assertThat(res.groupCount()).isZero();
        assertThat(res.sharedPhotoCount()).isZero();
        assertThat(res.upcomingEventCount()).isZero();
    }

    // ─── updateMe ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateMe — nickname 변경 시 반영")
    void updateMe_nicknameChanged() {
        User u = user(USER_ID, "기존닉", OauthProvider.KAKAO);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        UpdateMeRequest req = new UpdateMeRequest("새닉", null, null);
        UserMeResponse res = service.updateMe(USER_ID, req);

        assertThat(res.nickname()).isEqualTo("새닉");
    }

    @Test
    @DisplayName("updateMe — profileImageUrl 변경 시 반영")
    void updateMe_profileImageChanged() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        UpdateMeRequest req = new UpdateMeRequest(null, "https://cdn.example.com/img.jpg", null);
        UserMeResponse res = service.updateMe(USER_ID, req);

        assertThat(res.profileImageUrl()).isEqualTo("https://cdn.example.com/img.jpg");
    }

    @Test
    @DisplayName("updateMe — personalColor 변경 시 반영")
    void updateMe_personalColorChanged() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        UpdateMeRequest req = new UpdateMeRequest(null, null, "#FF5733");
        UserMeResponse res = service.updateMe(USER_ID, req);

        assertThat(res.personalColor()).isEqualTo("#FF5733");
    }

    @Test
    @DisplayName("updateMe — 없는 유저 시 USER_NOT_FOUND")
    void updateMe_userNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMe(USER_ID, new UpdateMeRequest("닉", null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(UserServiceTest::codeOf)
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("updateMe — null 필드는 기존값 유지")
    void updateMe_nullFieldsKeepOriginal() {
        User u = user(USER_ID, "기존닉", OauthProvider.KAKAO);
        ReflectionTestUtils.setField(u, "personalColor", "#AABBCC");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        UpdateMeRequest req = new UpdateMeRequest(null, null, null);
        UserMeResponse res = service.updateMe(USER_ID, req);

        assertThat(res.nickname()).isEqualTo("기존닉");
        assertThat(res.personalColor()).isEqualTo("#AABBCC");
    }

    // ─── updateExpoPushToken ────────────────────────────────────────────────────

    @Test
    @DisplayName("updateExpoPushToken — 토큰+플랫폼 정상 등록")
    void updateExpoPushToken_registerToken() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        service.updateExpoPushToken(USER_ID, "ExponentPushToken[abc123]", "ios");

        assertThat(u.getExpoPushToken()).isEqualTo("ExponentPushToken[abc123]");
        assertThat(u.getPushPlatform()).isEqualTo("ios");
    }

    @Test
    @DisplayName("updateExpoPushToken — null 토큰이면 토큰 제거")
    void updateExpoPushToken_nullClearsToken() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        service.updateExpoPushToken(USER_ID, null, null);

        assertThat(u.getExpoPushToken()).isNull();
    }

    @Test
    @DisplayName("updateExpoPushToken — 빈 문자열 토큰이면 제거")
    void updateExpoPushToken_blankClearsToken() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        service.updateExpoPushToken(USER_ID, "   ", "android");

        assertThat(u.getExpoPushToken()).isNull();
        // 토큰이 null 이면 플랫폼 갱신 안 함
        assertThat(u.getPushPlatform()).isNull();
    }

    @Test
    @DisplayName("updateExpoPushToken — 없는 유저 시 USER_NOT_FOUND")
    void updateExpoPushToken_userNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateExpoPushToken(USER_ID, "token", "ios"))
                .isInstanceOf(CustomException.class)
                .extracting(UserServiceTest::codeOf)
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ─── deleteMyAccount ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteMyAccount — 없는 유저 시 USER_NOT_FOUND")
    void deleteMyAccount_userNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMyAccount(USER_ID))
                .isInstanceOf(CustomException.class)
                .extracting(UserServiceTest::codeOf)
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("deleteMyAccount — OWNER 그룹은 groupService.delete 위임")
    void deleteMyAccount_ownerGroupIsDeleted() {
        long groupId = 10L;
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        Group ownerGroup = Group.builder()
                .name("내 그룹")
                .colorKey(GroupColor.pink)
                .createdBy(USER_ID)
                .personal(false)
                .build();
        ReflectionTestUtils.setField(ownerGroup, "id", groupId);

        GroupMember gm = GroupMember.builder()
                .groupId(groupId)
                .userId(USER_ID)
                .role(MemberRole.OWNER)
                .build();

        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of(gm));
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(ownerGroup));
        when(photoRepository.findAllByUploadedBy(USER_ID)).thenReturn(List.of());

        service.deleteMyAccount(USER_ID);

        verify(groupService).delete(USER_ID, groupId);
        verify(groupService, never()).leave(anyLong(), anyLong());
    }

    @Test
    @DisplayName("deleteMyAccount — 멤버 그룹은 groupService.leave 위임")
    void deleteMyAccount_memberGroupIsLeft() {
        long groupId = 20L;
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        Group otherGroup = Group.builder()
                .name("팀 그룹")
                .colorKey(GroupColor.team)
                .createdBy(99L)   // 다른 사람이 OWNER
                .personal(false)
                .build();
        ReflectionTestUtils.setField(otherGroup, "id", groupId);

        GroupMember gm = GroupMember.builder()
                .groupId(groupId)
                .userId(USER_ID)
                .role(MemberRole.MEMBER)
                .build();

        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of(gm));
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(otherGroup));
        when(photoRepository.findAllByUploadedBy(USER_ID)).thenReturn(List.of());

        service.deleteMyAccount(USER_ID);

        verify(groupService).leave(USER_ID, groupId);
        verify(groupService, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("deleteMyAccount — dangling 사진 row 삭제 + photoRepository.delete 호출")
    void deleteMyAccount_danglingPhotosDeleted() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of());

        Photo photo = Photo.builder()
                .groupId(5L)
                .uploadedBy(USER_ID)
                .storageKey("groups/5/abc.jpg")
                .mimeType("image/jpeg")
                .uploadedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();

        when(photoRepository.findAllByUploadedBy(USER_ID)).thenReturn(List.of(photo));

        service.deleteMyAccount(USER_ID);

        verify(photoRepository).delete(photo);
    }

    @Test
    @DisplayName("deleteMyAccount — pollVote / ddayPin / refreshToken / user row 삭제 수행")
    void deleteMyAccount_cleansUpAllData() {
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
        when(photoRepository.findAllByUploadedBy(USER_ID)).thenReturn(List.of());

        service.deleteMyAccount(USER_ID);

        verify(pollVoteRepository).deleteAllByUserId(USER_ID);
        verify(ddayPinRepository).deleteAllByUserId(USER_ID);
        verify(notificationService).deleteAllForUser(USER_ID);
        verify(refreshTokenStore).invalidate(USER_ID);
        verify(userRepository).delete(u);
    }

    @Test
    @DisplayName("deleteMyAccount — groupRepository에 없는 GroupMember는 건너뜀")
    void deleteMyAccount_skipsOrphanGroupMember() {
        long ghostGroupId = 999L;
        User u = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        GroupMember gm = GroupMember.builder()
                .groupId(ghostGroupId)
                .userId(USER_ID)
                .role(MemberRole.MEMBER)
                .build();

        when(groupMemberRepository.findAllByUserId(USER_ID)).thenReturn(List.of(gm));
        when(groupRepository.findById(ghostGroupId)).thenReturn(Optional.empty()); // 존재 안 함
        when(photoRepository.findAllByUploadedBy(USER_ID)).thenReturn(List.of());

        service.deleteMyAccount(USER_ID);

        verify(groupService, never()).delete(anyLong(), anyLong());
        verify(groupService, never()).leave(anyLong(), anyLong());
        verify(userRepository).delete(u);
    }
}

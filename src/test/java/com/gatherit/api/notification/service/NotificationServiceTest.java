package com.gatherit.api.notification.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.notification.dto.NotificationResponse;
import com.gatherit.api.notification.entity.Notification;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.entity.UserGroupNotifPref;
import com.gatherit.api.notification.entity.UserNotifPrefs;
import com.gatherit.api.notification.repository.NotificationRepository;
import com.gatherit.api.notification.repository.UserGroupNotifPrefRepository;
import com.gatherit.api.notification.repository.UserNotifPrefsRepository;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NotificationService 단위 테스트.
 *
 * 검증 포커스:
 *  - push/pushScoped/pushScopedToUsers: 수신 대상/제외 조건(allOff, groupContext, 미멤버, groupPref disabled/allowed)
 *  - dedup(PHOTO_UPLOADED): 윈도우 안 중복 시 push skip (DB 저장은 유지)
 *  - pushPollCompleted: pollCompletedEnabled=false 시 skip
 *  - markRead: 본인 소유가 아닌 알림 조회 → NOTIFICATION_NOT_FOUND
 *  - unreadCount: repo 위임 확인
 *  - deleteByRefKeys / deleteAllForUser / deleteForUserByRefKeys / delete: no-op 및 정상 경로
 *  - updateGroupPref: 멤버 아닐 때 NOT_GROUP_MEMBER
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final long USER_A  = 1L;
    private static final long USER_B  = 2L;
    private static final long USER_C  = 3L;
    private static final long GROUP_1 = 10L;
    private static final long GROUP_2 = 20L;
    private static final long NOTIF_ID = 100L;

    @Mock NotificationRepository       repository;
    @Mock UserNotifPrefsRepository     prefsRepository;
    @Mock UserGroupNotifPrefRepository groupPrefRepository;
    @Mock GroupMemberRepository        groupMemberRepository;
    @Mock ApnsService                  apnsService;
    @Mock FcmService                   fcmService;

    @InjectMocks NotificationService service;

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** allOff=false 인 기본 prefs (push 허용). */
    private UserNotifPrefs prefsOn(long userId) {
        return UserNotifPrefs.defaults(userId);        // allOff=false, quietHoursEnabled=false
    }

    /** allOff=true 인 prefs. */
    private UserNotifPrefs prefsOff(long userId) {
        UserNotifPrefs p = UserNotifPrefs.defaults(userId);
        p.update(true, null, null, null, null, null, null);
        return p;
    }

    /** pollCompletedEnabled=false 인 prefs. */
    private UserNotifPrefs prefsPollCompletedOff(long userId) {
        UserNotifPrefs p = UserNotifPrefs.defaults(userId);
        p.update(null, null, null, null, null, null, false);
        return p;
    }

    /** enabled=true, type별 all-allowed 인 기본 그룹 prefs. */
    private UserGroupNotifPref groupPrefOn(long userId, long groupId) {
        return UserGroupNotifPref.defaults(userId, groupId);
    }

    /** enabled=false 인 그룹 prefs. */
    private UserGroupNotifPref groupPrefDisabled(long userId, long groupId) {
        UserGroupNotifPref gp = UserGroupNotifPref.defaults(userId, groupId);
        gp.update(false, null, null, null, null, null, null, null, null);
        return gp;
    }

    /** eventUpdated=false(기본값) 인 그룹 prefs — enabled=true. */
    private UserGroupNotifPref groupPrefEventUpdatedOff(long userId, long groupId) {
        return UserGroupNotifPref.defaults(userId, groupId); // eventUpdated 기본이 false
    }

    private Notification notification(long id, long userId) {
        Notification n = Notification.builder()
                .userId(userId).type(NotificationType.EVENT_CREATED)
                .title("t").subtitle("s").refKey("event:1")
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    private GroupMember groupMember(long groupId, long userId) {
        GroupMember gm = mock(GroupMember.class);
        when(gm.getGroupId()).thenReturn(groupId);
        when(gm.getUserId()).thenReturn(userId);
        return gm;
    }

    /** prefsRepository.findById 스텁 — 존재하는 경우. */
    private void stubPrefs(long userId, UserNotifPrefs prefs) {
        when(prefsRepository.findById(userId)).thenReturn(Optional.of(prefs));
    }

    /** prefsRepository.findById 없음 → save 로 defaults 반환. */
    private void stubPrefsAbsent(long userId) {
        UserNotifPrefs defaults = UserNotifPrefs.defaults(userId);
        when(prefsRepository.findById(userId)).thenReturn(Optional.empty());
        when(prefsRepository.save(any(UserNotifPrefs.class))).thenReturn(defaults);
    }

    /** groupPrefRepository.findByUserIdAndGroupId 스텁. */
    private void stubGroupPref(long userId, long groupId, UserGroupNotifPref pref) {
        when(groupPrefRepository.findByUserIdAndGroupId(userId, groupId)).thenReturn(Optional.of(pref));
    }

    /** 그룹 pref 없음 → save 로 defaults 반환. */
    private void stubGroupPrefAbsent(long userId, long groupId) {
        when(groupPrefRepository.findByUserIdAndGroupId(userId, groupId)).thenReturn(Optional.empty());
        UserGroupNotifPref defaults = UserGroupNotifPref.defaults(userId, groupId);
        when(groupPrefRepository.save(any(UserGroupNotifPref.class))).thenReturn(defaults);
    }

    // ─── push ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("push — allOff=true 이면 저장하지 않고 return")
    void push_allOff_skips() {
        stubPrefs(USER_A, prefsOff(USER_A));

        service.push(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("push — allOff=false 이면 DB 에 저장")
    void push_allOn_saves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        // dedup: PHOTO_UPLOADED 아니라 skip
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.push(USER_A, NotificationType.EVENT_CREATED, "제목", "본문", "event:1");

        verify(repository).save(any(Notification.class));
    }

    // ─── pushScoped ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("pushScoped — allOff=true 이면 저장 안 함")
    void pushScoped_allOff_skips() {
        stubPrefs(USER_A, prefsOff(USER_A));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1",
                List.of(GROUP_1));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushScoped — groupContext 가 null 이면 그룹 체크 없이 저장")
    void pushScoped_nullGroupContext_saves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1", null);

        verify(repository).save(any(Notification.class));
        verify(groupMemberRepository, never()).existsByGroupIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("pushScoped — groupContext 가 비어 있으면 그룹 체크 없이 저장")
    void pushScoped_emptyGroupContext_saves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1", List.of());

        verify(repository).save(any(Notification.class));
        verify(groupMemberRepository, never()).existsByGroupIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("pushScoped — 그룹 멤버가 아니면 저장 안 함")
    void pushScoped_notGroupMember_skips() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(false);

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1",
                List.of(GROUP_1));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushScoped — 멤버이지만 그룹 prefs enabled=false 이면 저장 안 함")
    void pushScoped_groupPrefDisabled_skips() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_1, groupPrefDisabled(USER_A, GROUP_1));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1",
                List.of(GROUP_1));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushScoped — enabled=true 이지만 해당 type 이 false(EVENT_UPDATED) 이면 저장 안 함")
    void pushScoped_groupPrefTypeDisallowed_skips() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        // eventUpdated 는 기본 false
        stubGroupPref(USER_A, GROUP_1, groupPrefEventUpdatedOff(USER_A, GROUP_1));

        service.pushScoped(USER_A, NotificationType.EVENT_UPDATED, "t", "s", "event:1",
                List.of(GROUP_1));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushScoped — 멤버이고 그룹 prefs enabled+type 모두 true 이면 저장")
    void pushScoped_memberAndPrefAllowed_saves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_1, groupPrefOn(USER_A, GROUP_1));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "제목", "본문", "event:2",
                List.of(GROUP_1));

        verify(repository).save(argThat(n ->
                n.getUserId().equals(USER_A) &&
                n.getType() == NotificationType.EVENT_CREATED));
    }

    @Test
    @DisplayName("pushScoped — 두 그룹 중 하나만 허용해도 저장됨(OR 판정)")
    void pushScoped_multiGroup_anyAllowsSaves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        // GROUP_1: 멤버 아님
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(false);
        // GROUP_2: 멤버이고 허용
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_2, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_2, groupPrefOn(USER_A, GROUP_2));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.EVENT_CREATED, "t", "s", "event:1",
                List.of(GROUP_1, GROUP_2));

        verify(repository).save(any(Notification.class));
    }

    // ─── dedup (PHOTO_UPLOADED) ───────────────────────────────────────────────

    @Test
    @DisplayName("pushScoped(PHOTO_UPLOADED) — dedup 윈도우 안 기존 push 있으면 APNs/FCM 미호출, DB 저장은 유지")
    void pushScoped_photoUploaded_dedup_skipsPushButSaves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_1, groupPrefOn(USER_A, GROUP_1));

        // 이미 dedup 윈도우 안에 같은 알림 존재
        when(repository.existsByUserIdAndTypeAndRefKeyAndCreatedAtAfter(
                eq(USER_A), eq(NotificationType.PHOTO_UPLOADED), eq("photo:5"), any(Instant.class)))
                .thenReturn(true);
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.PHOTO_UPLOADED, "사진", null, "photo:5",
                List.of(GROUP_1));

        verify(repository).save(any(Notification.class));          // DB 저장은 수행
        verify(apnsService, never()).sendToUser(anyLong(), any(), any(), any());
        verify(fcmService, never()).sendToUser(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("pushScoped(PHOTO_UPLOADED) — dedup 윈도우 밖이면 APNs/FCM 모두 호출")
    void pushScoped_photoUploaded_noDedup_pushes() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_1, groupPrefOn(USER_A, GROUP_1));

        when(repository.existsByUserIdAndTypeAndRefKeyAndCreatedAtAfter(
                eq(USER_A), eq(NotificationType.PHOTO_UPLOADED), eq("photo:6"), any(Instant.class)))
                .thenReturn(false);
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.PHOTO_UPLOADED, "사진", null, "photo:6",
                List.of(GROUP_1));

        verify(apnsService).sendToUser(eq(USER_A), eq("사진"), isNull(), any());
        verify(fcmService).sendToUser(eq(USER_A), eq("사진"), isNull(), any());
    }

    @Test
    @DisplayName("pushScoped(PHOTO_UPLOADED) — refKey=null 이면 dedup 체크 건너뜀")
    void pushScoped_photoUploaded_nullRefKey_skipsDedup() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        stubGroupPref(USER_A, GROUP_1, groupPrefOn(USER_A, GROUP_1));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushScoped(USER_A, NotificationType.PHOTO_UPLOADED, "사진", null, null,
                List.of(GROUP_1));

        verify(repository, never()).existsByUserIdAndTypeAndRefKeyAndCreatedAtAfter(
                anyLong(), any(), any(), any());
        verify(apnsService).sendToUser(anyLong(), any(), any(), any());
    }

    // ─── pushScopedToUsers (fan-out) ─────────────────────────────────────────

    @Test
    @DisplayName("pushScopedToUsers — 빈 userIds 이면 즉시 return (no query)")
    void pushScopedToUsers_emptyList_noOp() {
        service.pushScopedToUsers(List.of(), NotificationType.EVENT_CREATED, "t", "s", "e:1", List.of(GROUP_1));

        verify(prefsRepository, never()).findAllById(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("pushScopedToUsers — allOff=true 인 유저는 저장 및 push 제외")
    void pushScopedToUsers_allOffUser_excluded() {
        UserNotifPrefs prefsA = prefsOff(USER_A);
        UserNotifPrefs prefsB = prefsOn(USER_B);

        when(prefsRepository.findAllById(any())).thenReturn(List.of(prefsA, prefsB));
        // B: 그룹 멤버
        GroupMember gmB = groupMember(GROUP_1, USER_B);
        when(groupMemberRepository.findAllByGroupIdInAndUserIdIn(any(), any())).thenReturn(List.of(gmB));
        // B: 그룹 pref 존재 + 허용
        UserGroupNotifPref gpB = groupPrefOn(USER_B, GROUP_1);
        when(groupPrefRepository.findAllByUserIdInAndGroupIdIn(any(), any())).thenReturn(List.of(gpB));
        when(repository.findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.pushScopedToUsers(List.of(USER_A, USER_B), NotificationType.EVENT_CREATED,
                "제목", "본문", "event:3", List.of(GROUP_1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<Notification> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(USER_B);

        verify(apnsService, never()).sendToUser(eq(USER_A), any(), any(), any());
        verify(apnsService).sendToUser(eq(USER_B), any(), any(), any());
    }

    @Test
    @DisplayName("pushScopedToUsers — 그룹 컨텍스트 안에 속하지 않은 유저(멤버 row 없음)는 제외")
    void pushScopedToUsers_nonMember_excluded() {
        UserNotifPrefs prefsA = prefsOn(USER_A);
        UserNotifPrefs prefsB = prefsOn(USER_B);
        when(prefsRepository.findAllById(any())).thenReturn(List.of(prefsA, prefsB));

        // B만 GROUP_1 멤버, A는 memberGroups 에 없음
        GroupMember gmB = groupMember(GROUP_1, USER_B);
        when(groupMemberRepository.findAllByGroupIdInAndUserIdIn(any(), any())).thenReturn(List.of(gmB));
        UserGroupNotifPref gpB = groupPrefOn(USER_B, GROUP_1);
        when(groupPrefRepository.findAllByUserIdInAndGroupIdIn(any(), any())).thenReturn(List.of(gpB));
        when(repository.findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.pushScopedToUsers(List.of(USER_A, USER_B), NotificationType.EVENT_CREATED,
                "t", "s", "event:4", List.of(GROUP_1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(Notification::getUserId).containsExactly(USER_B);
    }

    @Test
    @DisplayName("pushScopedToUsers — groupContext=null 이면 그룹 필터 없이 전원 저장")
    void pushScopedToUsers_nullContext_savesAll() {
        UserNotifPrefs prefsA = prefsOn(USER_A);
        UserNotifPrefs prefsB = prefsOn(USER_B);
        when(prefsRepository.findAllById(any())).thenReturn(List.of(prefsA, prefsB));

        service.pushScopedToUsers(List.of(USER_A, USER_B), NotificationType.EVENT_CREATED,
                "t", "s", "event:5", null);

        verify(groupMemberRepository, never()).findAllByGroupIdInAndUserIdIn(any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("pushScopedToUsers(PHOTO_UPLOADED) — dedup 대상 유저는 push skip, saveAll 에는 포함")
    void pushScopedToUsers_photoDedup_skipsPushForDedupUser() {
        UserNotifPrefs prefsA = prefsOn(USER_A);
        UserNotifPrefs prefsB = prefsOn(USER_B);
        when(prefsRepository.findAllById(any())).thenReturn(List.of(prefsA, prefsB));

        GroupMember gmA = groupMember(GROUP_1, USER_A);
        GroupMember gmB = groupMember(GROUP_1, USER_B);
        when(groupMemberRepository.findAllByGroupIdInAndUserIdIn(any(), any())).thenReturn(List.of(gmA, gmB));
        UserGroupNotifPref gpA = groupPrefOn(USER_A, GROUP_1);
        UserGroupNotifPref gpB = groupPrefOn(USER_B, GROUP_1);
        when(groupPrefRepository.findAllByUserIdInAndGroupIdIn(any(), any())).thenReturn(List.of(gpA, gpB));

        // A는 이미 dedup 윈도우 안에 기록 있음
        Notification existingA = Notification.builder()
                .userId(USER_A).type(NotificationType.PHOTO_UPLOADED).title("t").subtitle("s").refKey("photo:7")
                .build();
        when(repository.findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(any(), any(), eq("photo:7"), any()))
                .thenReturn(List.of(existingA));

        service.pushScopedToUsers(List.of(USER_A, USER_B), NotificationType.PHOTO_UPLOADED,
                "사진", null, "photo:7", List.of(GROUP_1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2); // 저장은 둘 다

        // A: dedup → push skip
        verify(apnsService, never()).sendToUser(eq(USER_A), any(), any(), any());
        verify(fcmService, never()).sendToUser(eq(USER_A), any(), any(), any());
        // B: push 수행
        verify(apnsService).sendToUser(eq(USER_B), any(), any(), any());
        verify(fcmService).sendToUser(eq(USER_B), any(), any(), any());
    }

    @Test
    @DisplayName("pushScopedToUsers — 중복 userIds 는 distinct 처리")
    void pushScopedToUsers_duplicateIds_deduped() {
        UserNotifPrefs prefsA = prefsOn(USER_A);
        when(prefsRepository.findAllById(any())).thenReturn(List.of(prefsA));
        when(groupMemberRepository.findAllByGroupIdInAndUserIdIn(any(), any())).thenReturn(List.of());
        when(groupPrefRepository.findAllByUserIdInAndGroupIdIn(any(), any())).thenReturn(List.of());
        when(repository.findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(any(), any(), any(), any()))
                .thenReturn(List.of());

        // 같은 id 3번 넘김 — hasContext=true 이고 memberGroups 비어있어 anyAllow=false → 저장 안 됨
        service.pushScopedToUsers(List.of(USER_A, USER_A, USER_A), NotificationType.EVENT_CREATED,
                "t", "s", "e:1", List.of(GROUP_1));

        // findAllByGroupIdInAndUserIdIn 에 넘기는 ids 가 distinct 1개인지 캡처로 확인
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(groupMemberRepository).findAllByGroupIdInAndUserIdIn(any(), idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(USER_A);
    }

    // ─── pushPollCompleted ───────────────────────────────────────────────────

    @Test
    @DisplayName("pushPollCompleted — allOff=true 이면 skip")
    void pushPollCompleted_allOff_skips() {
        stubPrefs(USER_A, prefsOff(USER_A));

        service.pushPollCompleted(USER_A, "투표 확정", null, "poll:1");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushPollCompleted — pollCompletedEnabled=false 이면 skip")
    void pushPollCompleted_pollCompletedOff_skips() {
        stubPrefs(USER_A, prefsPollCompletedOff(USER_A));

        service.pushPollCompleted(USER_A, "투표 확정", null, "poll:1");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("pushPollCompleted — allOff=false, pollCompletedEnabled=true 이면 저장")
    void pushPollCompleted_enabled_saves() {
        stubPrefs(USER_A, prefsOn(USER_A));
        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pushPollCompleted(USER_A, "투표 확정", "내용", "poll:2");

        verify(repository).save(argThat(n ->
                n.getType() == NotificationType.POLL_COMPLETED &&
                n.getUserId().equals(USER_A)));
    }

    // ─── markRead ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("markRead — 존재하지 않거나 본인 소유 아닌 알림 → NOTIFICATION_NOT_FOUND")
    void markRead_notFound_throws() {
        when(repository.findByIdAndUserId(NOTIF_ID, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(USER_A, NOTIF_ID))
                .isInstanceOf(CustomException.class)
                .extracting(t -> ((CustomException) t).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("markRead — 본인 소유 알림은 readAt 세팅")
    void markRead_own_setsReadAt() {
        Notification n = notification(NOTIF_ID, USER_A);
        when(repository.findByIdAndUserId(NOTIF_ID, USER_A)).thenReturn(Optional.of(n));

        service.markRead(USER_A, NOTIF_ID);

        assertThat(n.getReadAt()).isNotNull();
    }

    // ─── markAllRead ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("markAllRead — markAllReadByUserId 호출")
    void markAllRead_delegatesToRepo() {
        service.markAllRead(USER_A);

        verify(repository).markAllReadByUserId(eq(USER_A), any(Instant.class));
    }

    // ─── unreadCount ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("unreadCount — repo 카운트 그대로 반환")
    void unreadCount_delegatesToRepo() {
        when(repository.countByUserIdAndReadAtIsNull(USER_A)).thenReturn(5L);

        assertThat(service.unreadCount(USER_A)).isEqualTo(5L);
    }

    // ─── list ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list — findTop200... 결과를 NotificationResponse 리스트로 반환")
    void list_returnsResponses() {
        Notification n = notification(NOTIF_ID, USER_A);
        when(repository.findTop200ByUserIdOrderByCreatedAtDesc(USER_A)).thenReturn(List.of(n));

        List<NotificationResponse> result = service.list(USER_A);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(NOTIF_ID);
    }

    // ─── delete 계열 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteByRefKeys — 빈 컬렉션이면 repo 호출 없이 return")
    void deleteByRefKeys_empty_noOp() {
        service.deleteByRefKeys(List.of());
        verify(repository, never()).deleteAllByRefKeyIn(any());
    }

    @Test
    @DisplayName("deleteByRefKeys — 정상 컬렉션은 repo 위임")
    void deleteByRefKeys_delegates() {
        service.deleteByRefKeys(List.of("poll:1", "event:2"));
        verify(repository).deleteAllByRefKeyIn(List.of("poll:1", "event:2"));
    }

    @Test
    @DisplayName("deleteAllForUser — repo 위임")
    void deleteAllForUser_delegates() {
        service.deleteAllForUser(USER_A);
        verify(repository).deleteAllByUserId(USER_A);
    }

    @Test
    @DisplayName("deleteForUserByRefKeys — 빈 컬렉션이면 repo 호출 없이 return")
    void deleteForUserByRefKeys_empty_noOp() {
        service.deleteForUserByRefKeys(USER_A, List.of());
        verify(repository, never()).deleteAllByUserIdAndRefKeyIn(anyLong(), any());
    }

    @Test
    @DisplayName("deleteForUserByRefKeys — 정상 입력은 repo 위임")
    void deleteForUserByRefKeys_delegates() {
        service.deleteForUserByRefKeys(USER_A, List.of("group:10"));
        verify(repository).deleteAllByUserIdAndRefKeyIn(USER_A, List.of("group:10"));
    }

    @Test
    @DisplayName("delete — 본인 소유 알림이면 삭제")
    void delete_own_deletes() {
        Notification n = notification(NOTIF_ID, USER_A);
        when(repository.findByIdAndUserId(NOTIF_ID, USER_A)).thenReturn(Optional.of(n));

        service.delete(USER_A, NOTIF_ID);

        verify(repository).delete(n);
    }

    @Test
    @DisplayName("delete — 알림이 없으면(다른 유저 포함) idempotent no-op")
    void delete_notFound_noOp() {
        when(repository.findByIdAndUserId(NOTIF_ID, USER_A)).thenReturn(Optional.empty());

        service.delete(USER_A, NOTIF_ID);

        verify(repository, never()).delete(any(Notification.class));
    }

    // ─── updateGroupPref ─────────────────────────────────────────────────────

    @Test
    @DisplayName("updateGroupPref — 멤버 아니면 NOT_GROUP_MEMBER")
    void updateGroupPref_notMember_throws() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(false);

        assertThatThrownBy(() -> service.updateGroupPref(USER_A, GROUP_1,
                new com.gatherit.api.notification.dto.GroupNotifPrefUpdateRequest(
                        true, true, true, true, true, true, true, true, true)))
                .isInstanceOf(CustomException.class)
                .extracting(t -> ((CustomException) t).getErrorCode())
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("updateGroupPref — 멤버이면 pref 업데이트 후 응답 반환")
    void updateGroupPref_member_updates() {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP_1, USER_A)).thenReturn(true);
        UserGroupNotifPref gp = groupPrefOn(USER_A, GROUP_1);
        stubGroupPref(USER_A, GROUP_1, gp);
        when(groupPrefRepository.findAllByUserId(USER_A)).thenReturn(List.of(gp));
        stubPrefs(USER_A, prefsOn(USER_A));

        var req = new com.gatherit.api.notification.dto.GroupNotifPrefUpdateRequest(
                false, true, false, true, true, true, true, true, true);
        var resp = service.updateGroupPref(USER_A, GROUP_1, req);

        assertThat(resp).isNotNull();
        assertThat(gp.isEnabled()).isFalse();
    }
}

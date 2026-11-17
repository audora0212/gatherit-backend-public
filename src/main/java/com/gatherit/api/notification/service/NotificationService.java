package com.gatherit.api.notification.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.notification.dto.GroupNotifPrefResponse;
import com.gatherit.api.notification.dto.GroupNotifPrefUpdateRequest;
import com.gatherit.api.notification.dto.NotificationResponse;
import com.gatherit.api.notification.dto.UserNotifPrefsResponse;
import com.gatherit.api.notification.dto.UserNotifPrefsUpdateRequest;
import com.gatherit.api.notification.entity.Notification;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.entity.UserGroupNotifPref;
import com.gatherit.api.notification.entity.UserNotifPrefs;
import com.gatherit.api.notification.repository.NotificationRepository;
import com.gatherit.api.notification.repository.UserGroupNotifPrefRepository;
import com.gatherit.api.notification.repository.UserNotifPrefsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;
    private final UserNotifPrefsRepository prefsRepository;
    private final UserGroupNotifPrefRepository groupPrefRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ApnsService apnsService;
    private final FcmService fcmService;

    /**
     * 같은 user/type/refKey 알림이 이 윈도우 안에 이미 있으면 추가 push 안 함 (DB record 는 저장).
     * v1.1.1: 사진 batch 업로드 연발 시 멤버에게 매번 push 가는 폭증 방지.
     * 적용 type: PHOTO_UPLOADED (확장 시 isDeduplicatable 로 분리).
     */
    private static final Duration PUSH_DEDUP_WINDOW = Duration.ofMinutes(5);

    /** Quiet hours 판정 기준 타임존 — 서버 운영 지역. v1.2 에 user.timezone 으로 확장 검토. */
    private static final ZoneId QUIET_HOURS_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 그룹 컨텍스트 없는 단순 push — 현재 사용 케이스 거의 없음.
     * allOff 만 체크하고 발사 (가입 안내 등).
     */
    @Transactional
    public void push(Long userId, NotificationType type, String title, String subtitle, String refKey) {
        UserNotifPrefs prefs = prefsOf(userId);
        if (prefs.isAllOff()) return;
        save(userId, type, title, subtitle, refKey);
    }

    /**
     * 그룹 컨텍스트 있는 push — 핵심 진입점.
     *
     * 분기:
     *  1. user.allOff → skip
     *  2. groupContext 가 비어있음 → 발사 (그룹 무관 알림)
     *  3. user 가 멤버인 groupContext 그룹들 중 하나라도 enabled=true 이고 해당 type=true → 발사
     *  4. 그렇지 않으면 → skip
     */
    @Transactional
    public void pushScoped(Long userId, NotificationType type, String title, String subtitle, String refKey,
                           Collection<Long> groupContext) {
        UserNotifPrefs prefs = prefsOf(userId);
        if (prefs.isAllOff()) return;

        if (groupContext != null && !groupContext.isEmpty()) {
            boolean anyAllow = false;
            for (Long gid : groupContext) {
                if (!groupMemberRepository.existsByGroupIdAndUserId(gid, userId)) continue;
                UserGroupNotifPref gp = groupPrefOf(userId, gid);
                if (gp.isEnabled() && gp.isAllowed(type)) {
                    anyAllow = true;
                    break;
                }
            }
            if (!anyAllow) return;
        }
        save(userId, type, title, subtitle, refKey);
    }

    /**
     * pushScoped 의 일괄판 — 그룹 fan-out 전용 (P2-8).
     * 멤버당 개별 쿼리(prefs 2회 + 그룹별 멤버십/그룹prefs + dedup)를 IN-query 4번 선조회로 대체.
     * 판정 로직은 pushScoped/save 와 동일. 미존재 prefs 는 기본값으로 판정만 하고 저장하지 않는다
     * (기본값은 결정적이라 결과 동일 — 영속화는 기존 개별 경로가 필요할 때 수행).
     */
    @Transactional
    public void pushScopedToUsers(Collection<Long> userIds, NotificationType type, String title, String subtitle,
                                  String refKey, Collection<Long> groupContext) {
        if (userIds == null || userIds.isEmpty()) return;
        List<Long> ids = userIds.stream().distinct().toList();

        // 선조회 1 — 전역 prefs
        Map<Long, UserNotifPrefs> prefsById = new HashMap<>();
        prefsRepository.findAllById(ids).forEach(p -> prefsById.put(p.getUserId(), p));

        // 선조회 2/3 — 컨텍스트 그룹 멤버십 + 그룹 prefs
        Map<Long, Set<Long>> memberGroups = new HashMap<>();          // userId → 속한 컨텍스트 그룹들
        Map<Long, Map<Long, UserGroupNotifPref>> groupPrefs = new HashMap<>(); // userId → (groupId → pref)
        boolean hasContext = groupContext != null && !groupContext.isEmpty();
        if (hasContext) {
            for (var gm : groupMemberRepository.findAllByGroupIdInAndUserIdIn(groupContext, ids)) {
                memberGroups.computeIfAbsent(gm.getUserId(), k -> new HashSet<>()).add(gm.getGroupId());
            }
            for (UserGroupNotifPref gp : groupPrefRepository.findAllByUserIdInAndGroupIdIn(ids, groupContext)) {
                groupPrefs.computeIfAbsent(gp.getUserId(), k -> new HashMap<>()).put(gp.getGroupId(), gp);
            }
        }

        // 선조회 4 — push dedup (PHOTO_UPLOADED 만)
        Set<Long> dedupUsers = new HashSet<>();
        if (isDeduplicatable(type) && refKey != null) {
            Instant since = Instant.now().minus(PUSH_DEDUP_WINDOW);
            repository.findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(ids, type, refKey, since)
                    .forEach(n -> dedupUsers.add(n.getUserId()));
        }

        int quietHour = LocalTime.now(QUIET_HOURS_ZONE).getHour();
        List<Notification> toSave = new java.util.ArrayList<>();
        List<Long> toPush = new java.util.ArrayList<>();
        for (Long uid : ids) {
            UserNotifPrefs prefs = prefsById.getOrDefault(uid, UserNotifPrefs.defaults(uid));
            if (prefs.isAllOff()) continue;

            if (hasContext) {
                boolean anyAllow = false;
                for (Long gid : memberGroups.getOrDefault(uid, Set.of())) {
                    UserGroupNotifPref gp = groupPrefs.getOrDefault(uid, Map.of()).get(gid);
                    if (gp == null) gp = UserGroupNotifPref.defaults(uid, gid);
                    if (gp.isEnabled() && gp.isAllowed(type)) { anyAllow = true; break; }
                }
                if (!anyAllow) continue;
            }

            toSave.add(Notification.builder()
                    .userId(uid).type(type).title(title).subtitle(subtitle).refKey(refKey)
                    .build());
            if (!dedupUsers.contains(uid) && !prefs.isQuietAt(quietHour)) toPush.add(uid);
        }
        if (toSave.isEmpty()) return;
        repository.saveAll(toSave);

        Map<String, Object> data = new HashMap<>();
        data.put("type", type.name());
        data.put("kind", type.name());
        if (refKey != null) data.put("refKey", refKey);
        for (Long uid : toPush) {
            apnsService.sendToUser(uid, title, subtitle, data);
            fcmService.sendToUser(uid, title, subtitle, data);
        }
    }

    /**
     * POLL_COMPLETED — 발의자 본인에게 (내가 만든 투표 확정/마감). 그룹 컨텍스트 아닌 전역 prefs 로 판정.
     * allOff 또는 pollCompletedEnabled=false 면 skip.
     */
    @Transactional
    public void pushPollCompleted(Long userId, String title, String subtitle, String refKey) {
        UserNotifPrefs prefs = prefsOf(userId);
        if (prefs.isAllOff()) return;
        if (!prefs.isPollCompletedEnabled()) return;
        save(userId, NotificationType.POLL_COMPLETED, title, subtitle, refKey);
    }

    // ─── 정리(삭제) — 죽은 참조 알림 근본 차단 (v1.3.1 #1) ─────────────────────

    /**
     * poll/event 하드 삭제 시 모든 수신자의 해당 refKey 알림 일괄 제거.
     * refKey 예: "poll:42", "event:99". 빈 컬렉션이면 no-op.
     */
    @Transactional
    public void deleteByRefKeys(Collection<String> refKeys) {
        if (refKeys == null || refKeys.isEmpty()) return;
        repository.deleteAllByRefKeyIn(refKeys);
    }

    /** 탈퇴 유저의 수신 알림 전부 삭제. */
    @Transactional
    public void deleteAllForUser(Long userId) {
        repository.deleteAllByUserId(userId);
    }

    /**
     * 특정 수신자가 받은 해당 refKey 알림만 삭제 — 그룹 탈퇴/강퇴 시, 떠난 그룹의 group:/photo: 알림은
     * 그 멤버에겐 더 못 보는 죽은 참조가 됨. 그룹은 살아있을 수 있어 전역(deleteByRefKeys) 대신 수신자별로 지운다.
     */
    @Transactional
    public void deleteForUserByRefKeys(Long userId, Collection<String> refKeys) {
        if (refKeys == null || refKeys.isEmpty()) return;
        repository.deleteAllByUserIdAndRefKeyIn(userId, refKeys);
    }

    /** 단건 삭제(dismiss) — 본인 소유만, 없으면 idempotent no-op. */
    @Transactional
    public void delete(Long userId, Long id) {
        repository.findByIdAndUserId(id, userId).ifPresent(repository::delete);
    }

    private void save(Long userId, NotificationType type, String title, String subtitle, String refKey) {
        // 푸시 dedup — 같은 user/type/refKey 가 윈도우 안에 있으면 push 만 skip (DB record 는 그대로 저장).
        // 앱 내 알림 목록에는 살아남아 사용자가 앱 열면 정상 확인 가능.
        boolean shouldPush = true;
        if (isDeduplicatable(type) && refKey != null) {
            Instant since = Instant.now().minus(PUSH_DEDUP_WINDOW);
            if (repository.existsByUserIdAndTypeAndRefKeyAndCreatedAtAfter(userId, type, refKey, since)) {
                shouldPush = false;
            }
        }

        // 방해금지 — 현재 시각이 사용자 설정 quiet hours 안이면 push skip (DB record 는 유지).
        if (shouldPush) {
            UserNotifPrefs prefs = prefsOf(userId);
            int hour = LocalTime.now(QUIET_HOURS_ZONE).getHour();
            if (prefs.isQuietAt(hour)) {
                shouldPush = false;
            }
        }

        Notification n = Notification.builder()
                .userId(userId).type(type).title(title).subtitle(subtitle).refKey(refKey)
                .build();
        repository.save(n);

        if (!shouldPush) return;

        // 원격 푸시 — Apple APNs HTTP/2 직접 호출 (ApnsService).
        // Expo Push Service 사용 시 알림 아이콘이 Expo default 로 뜨는 한계 회피.
        // token 없거나 .p8 미설정이면 silent skip. @Async 라 메인 트랜잭션 영향 X.
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.name());
        data.put("kind", type.name());   // 앱 탭 핸들러가 읽는 키 — 원격 푸시 딥링크 라우팅용
        if (refKey != null) data.put("refKey", refKey);
        // 플랫폼 분기 — 각 서비스가 user.pushPlatform 으로 자기 대상만 처리(둘 다 @Async, 비대상은 즉시 return).
        apnsService.sendToUser(userId, title, subtitle, data);   // iOS / null(레거시)
        fcmService.sendToUser(userId, title, subtitle, data);    // android
    }

    private static boolean isDeduplicatable(NotificationType type) {
        return type == NotificationType.PHOTO_UPLOADED;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId) {
        return repository.findTop200ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(Long userId, Long notiId) {
        Notification n = repository.findByIdAndUserId(notiId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        n.markRead();
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllReadByUserId(userId, Instant.now());
    }

    // ─── prefs 조회/수정 ─────────────────────────────────────

    @Transactional
    public UserNotifPrefsResponse getPrefs(Long userId) {
        UserNotifPrefs top = prefsOf(userId);
        Map<Long, GroupNotifPrefResponse> groupPrefs = new HashMap<>();
        for (UserGroupNotifPref gp : groupPrefRepository.findAllByUserId(userId)) {
            groupPrefs.put(gp.getGroupId(), GroupNotifPrefResponse.from(gp));
        }
        return UserNotifPrefsResponse.from(top, groupPrefs);
    }

    @Transactional
    public UserNotifPrefsResponse updatePrefs(Long userId, UserNotifPrefsUpdateRequest req) {
        UserNotifPrefs prefs = prefsOf(userId);
        prefs.update(
                req.allOff(),
                req.leadTimeMinutes(),
                req.quietHoursEnabled(),
                req.quietHoursStart(),
                req.quietHoursEnd(),
                req.allDayReminderDays(),
                req.pollCompletedEnabled());
        return getPrefs(userId);
    }

    @Transactional
    public GroupNotifPrefResponse updateGroupPref(Long userId, Long groupId, GroupNotifPrefUpdateRequest req) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
        }
        UserGroupNotifPref gp = groupPrefOf(userId, groupId);
        gp.update(
                req.enabled(),
                req.eventCreated(), req.eventUpdated(),
                req.pollCreated(), req.pollDeadlineSoon(), req.pollConfirmed(),
                req.photoUploaded(), req.photoExpiring(), req.groupJoinedBy()
        );
        return GroupNotifPrefResponse.from(gp);
    }

    private UserNotifPrefs prefsOf(Long userId) {
        return prefsRepository.findById(userId)
                .orElseGet(() -> prefsRepository.save(UserNotifPrefs.defaults(userId)));
    }

    private UserGroupNotifPref groupPrefOf(Long userId, Long groupId) {
        return groupPrefRepository.findByUserIdAndGroupId(userId, groupId)
                .orElseGet(() -> groupPrefRepository.save(UserGroupNotifPref.defaults(userId, groupId)));
    }
}

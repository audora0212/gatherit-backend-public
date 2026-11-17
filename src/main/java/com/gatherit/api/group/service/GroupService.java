package com.gatherit.api.group.service;

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
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.photo.storage.StorageCleanup;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollGuestParticipant;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.PollVote;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.poll.repository.PollGuestVoteRepository;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final InviteTokenStore inviteTokenStore;
    private final EventRepository eventRepository;
    private final EventGroupRepository eventGroupRepository;
    private final NotificationService notificationService;
    private final PollRepository pollRepository;
    private final PollSlotRepository pollSlotRepository;
    private final PollVoteRepository pollVoteRepository;
    private final PollGuestParticipantRepository pollGuestParticipantRepository;
    private final PollGuestVoteRepository pollGuestVoteRepository;
    private final PhotoRepository photoRepository;
    private final PhotoStorage photoStorage;

    @Transactional
    public GroupResponse create(Long userId, GroupCreateRequest req) {
        Group group = Group.builder()
                .name(req.name())
                .colorKey(req.colorKey())  // group 자체의 default 색 — 새 멤버 join 시 fallback 후보
                .createdBy(userId)
                .personal(false)
                .build();
        groupRepository.save(group);

        // 생성자가 명시적으로 고른 색을 본인 멤버 색으로 그대로 사용 (auto-pick 으로 덮어쓰지 않음).
        GroupColor selected = req.colorKey();
        memberRepository.save(GroupMember.builder()
                .groupId(group.getId())
                .userId(userId)
                .role(MemberRole.OWNER)
                .colorKey(selected)
                .build());

        return GroupResponse.of(group, 1L, selected);
    }

    /**
     * 회원가입 시 자동으로 호출되는 "내 캘린더" 개인 그룹 생성.
     * - 초대 토큰 발급/멤버 추가/탈퇴 차단 (personal=true 인 그룹은 1인 고정)
     * - 색은 라일락(team) — 가장 첫 그룹이라 충돌 0
     */
    @Transactional
    public Group createPersonalGroup(Long userId) {
        Group group = Group.builder()
                .name("내 캘린더")
                .colorKey(GroupColor.team)
                .createdBy(userId)
                .personal(true)
                .build();
        groupRepository.save(group);

        memberRepository.save(GroupMember.builder()
                .groupId(group.getId())
                .userId(userId)
                .role(MemberRole.OWNER)
                .colorKey(GroupColor.team)
                .build());
        return group;
    }

    /**
     * 본인이 안 쓰는 색 중 enum 정의 순으로 첫 번째 — 모두 사용 중이면 team 으로 fallback.
     */
    private GroupColor pickUnusedColorForUser(Long userId) {
        java.util.Set<GroupColor> used = new java.util.HashSet<>();
        for (GroupMember gm : memberRepository.findAllByUserId(userId)) {
            if (gm.getColorKey() != null) used.add(gm.getColorKey());
        }
        for (GroupColor c : GroupColor.values()) {
            if (!used.contains(c)) return c;
        }
        return GroupColor.team;
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> findMyGroups(Long userId) {
        List<Group> groups = groupRepository.findAllByMemberUserId(userId);
        // 본인의 GroupMember row 한 번에 조회 → groupId → colorKey 맵
        Map<Long, GroupColor> colorByGroup = new HashMap<>();
        for (GroupMember gm : memberRepository.findAllByUserId(userId)) {
            colorByGroup.put(gm.getGroupId(), gm.getColorKey());
        }
        return groups.stream()
                .map(g -> GroupResponse.of(g, memberRepository.countByGroupId(g.getId()), colorByGroup.get(g.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getDetail(Long userId, Long groupId) {
        Group group = loadGroupAndAssertMember(userId, groupId);
        GroupColor userColor = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMember::getColorKey)
                .orElse(null);
        return GroupResponse.of(group, memberRepository.countByGroupId(groupId), userColor);
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listMembers(Long userId, Long groupId) {
        loadGroupAndAssertMember(userId, groupId);

        List<GroupMember> members = memberRepository.findAllByGroupId(groupId);
        // user 닉네임/프사 한 번에 조회 — IN query 로 N+1 방지
        List<Long> userIds = members.stream().map(GroupMember::getUserId).toList();
        Map<Long, String> nicknameById = new HashMap<>();
        Map<Long, String> profileImgById = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> {
            nicknameById.put(u.getId(), u.getNickname());
            profileImgById.put(u.getId(), u.getProfileImageUrl());
        });

        return members.stream()
                .map(gm -> GroupMemberResponse.of(
                        gm,
                        nicknameById.getOrDefault(gm.getUserId(), "(알 수 없음)"),
                        profileImgById.get(gm.getUserId())))
                .toList();
    }

    @Transactional
    public GroupResponse update(Long userId, Long groupId, GroupUpdateRequest req) {
        Group group = loadGroupAndAssertOwner(userId, groupId);
        if (req.name() != null) group.rename(req.name());
        if (req.photoDeletePolicy() != null) group.setPhotoDeletePolicy(req.photoDeletePolicy());
        if (req.invitePolicy() != null) group.setInvitePolicy(req.invitePolicy());
        if (req.eventCreatePolicy() != null) group.setEventCreatePolicy(req.eventCreatePolicy());
        if (req.pollCreatePolicy() != null) group.setPollCreatePolicy(req.pollCreatePolicy());
        if (req.publicPollJoinAllowed() != null) {
            group.setPublicPollJoinAllowed(req.publicPollJoinAllowed());
        }
        // colorKey 는 group 자체에 set 안 함 — 색은 per-user. 호환성 위해 무시.
        GroupColor userColor = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .map(GroupMember::getColorKey)
                .orElse(null);
        return GroupResponse.of(group, memberRepository.countByGroupId(groupId), userColor);
    }

    /**
     * 그룹 삭제 — OWNER 만. 멤버 전원에 대해 cleanup 후 그룹/잔여 데이터 정리.
     * 정책: 모든 멤버가 동시에 떠나는 것과 동일 효과 + 그룹 entity 자체 삭제.
     */
    @Transactional
    public void delete(Long userId, Long groupId) {
        Group group = loadGroupAndAssertOwner(userId, groupId);

        // 1. 멤버 전원의 그룹 한정 데이터 cleanup (idempotent)
        List<Long> memberIds = memberRepository.findAllByGroupId(group.getId()).stream()
                .map(GroupMember::getUserId)
                .toList();
        for (Long memberId : memberIds) {
            cleanupMemberDataInGroup(memberId, group.getId());
        }

        // 2. 잔여 EventGroup row (위 cleanup 에서 안 잡힌 케이스 — 안전망)
        eventGroupRepository.deleteAllByGroupId(group.getId());

        // 3. 그룹의 잔여 폴 (다른 사람이 만든 거 — cleanup 에서 삭제됐겠지만 안전망)
        for (Poll p : pollRepository.findAllByGroupId(group.getId())) {
            pollVoteRepository.deleteAllByPollId(p.getId());
            pollSlotRepository.deleteAllByPollId(p.getId());
            pollRepository.delete(p);
        }

        // 4. 그룹의 잔여 사진 (cleanup 에서 잡혔을 거지만 보강) — S3 파일은 커밋 후 (P1-5)
        List<String> residualKeys = new ArrayList<>();
        for (Photo photo : photoRepository.findAllByGroupId(group.getId())) {
            residualKeys.add(photo.getStorageKey());
            photoRepository.delete(photo);
        }
        StorageCleanup.deleteAfterCommit(photoStorage, residualKeys);

        // 5. 멤버 row 일괄 삭제 + 그룹 삭제
        memberRepository.deleteByGroupId(group.getId());
        groupRepository.delete(group);
    }

    @Transactional
    public void leave(Long userId, Long groupId) {
        GroupMember gm = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_GROUP_MEMBER));
        if (gm.isOwner()) {
            // owner 는 그룹 삭제 또는 소유권 위임 (Wave 후속) 후만 가능
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }
        cleanupMemberDataInGroup(userId, groupId);
        memberRepository.delete(gm);
    }

    @Transactional
    public void kick(Long userId, Long groupId, Long targetUserId) {
        loadGroupAndAssertOwner(userId, groupId);
        if (userId.equals(targetUserId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE); // 자기 자신 강퇴 시도 = leave 에러로
        }
        GroupMember gm = memberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_GROUP_MEMBER));
        cleanupMemberDataInGroup(targetUserId, groupId);
        memberRepository.delete(gm);
    }

    /** 그룹장 양도 — 현 OWNER 가 다른 멤버에게 소유권 이전. createdBy + 양쪽 role 교체. */
    @Transactional
    public void transferOwner(Long userId, Long groupId, Long targetUserId) {
        Group group = loadGroupAndAssertOwner(userId, groupId);
        if (userId.equals(targetUserId)) return; // 이미 본인이 owner — no-op
        GroupMember target = memberRepository.findByGroupIdAndUserId(groupId, targetUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_GROUP_MEMBER));
        GroupMember current = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_GROUP_MEMBER));
        group.changeOwner(targetUserId);
        target.changeRole(MemberRole.OWNER);
        current.changeRole(MemberRole.MEMBER);
    }

    /**
     * 떠나기/강퇴/그룹삭제 시 — 한 멤버의 "이 그룹에 한정된" 본인 데이터 cleanup.
     *
     * 정책 (확정): 탈퇴자가 "유발한" 것은 전부 제거. 남이 인지하고 만든 것(다른 사람 createdBy)은 유지.
     *  - 본인이 이 그룹에 만든 origin 일정:
     *      - 단일그룹 → 일정 삭제
     *      - 다중그룹 → 이 그룹 연결만 제거(원본은 다른 그룹에 유지)
     *      - 두 경우 모두 그 origin 기반 그림자(알림 복사본)는 cascade 삭제 — 탈퇴자 일정의 흔적이므로.
     *  - 본인이 이 그룹에 (다른 사람 일정 기반으로) 만든 그림자 → 삭제
     *  - 본인이 이 그룹에 만든 폴 → 삭제 (slots + votes 포함)
     *  - 본인이 이 그룹의 (남이 만든) 폴들에 한 응답 → 삭제 (응답률 카운트 정확)
     *  - 본인이 이 그룹에 올린 사진 → S3 + DB 삭제
     *
     * 폴↔일정 댕글링 양방향 정리 (createdBy 기준 소유권이라 일정 자체는 안 지우되, 죽은 참조만 끊음):
     *  - (방향1) 위에서 삭제된 일정을 linkedEvent 로 가진 폴 → linkedEventId 리셋
     *            (탈퇴자가 먼저 추가했던 확정 일정이 삭제돼도, 남은 멤버가 재등록 가능하게)
     *  - (방향2) 삭제된 폴에서 파생됐지만 다른 사람이 추가해 살아남는 일정 → fromPollId 끊어 평범한 일정으로 독립
     *
     * 알림은 일부러 안 보냄 — 떠남/삭제/강퇴 모두.
     * idempotent — 두 번 호출돼도 빈 결과면 그대로.
     */
    private void cleanupMemberDataInGroup(Long userId, Long groupId) {
        // 하드 삭제될 poll/event refKey 수집 → 마지막에 모든 수신자의 죽은 참조 알림 일괄 삭제(v1.3.1 #1).
        List<String> staleRefKeys = new ArrayList<>();
        // 하드 삭제된 event id 수집 → 이 일정을 linkedEvent 로 가진 폴의 linkedEventId 리셋(방향1) 용.
        List<Long> deletedEventIds = new ArrayList<>();

        // 1. 본인 origin 일정 + 그 일정의 모든 그림자
        List<Event> myOriginEvents = eventRepository.findOriginEventsByCreatedByAndGroupId(userId, groupId);
        for (Event ev : myOriginEvents) {
            // 1a. 이 일정 기반 모든 그림자 (누가 만들었든) cascade — 단일/다중 무관(탈퇴자 일정 흔적 제거)
            for (Event s : eventRepository.findByShadowOfEventId(ev.getId())) {
                eventGroupRepository.deleteAllByEventId(s.getId());
                eventRepository.delete(s);
                deletedEventIds.add(s.getId());
            }
            // 1b. 단일그룹이면 일정 자체 삭제, 다중이면 이 그룹 연결만 제거(원본은 다른 그룹에 유지)
            List<EventGroup> egs = eventGroupRepository.findAllByEventId(ev.getId());
            if (egs.size() <= 1) {
                eventGroupRepository.deleteAllByEventId(ev.getId());
                eventRepository.delete(ev);
                deletedEventIds.add(ev.getId());
                staleRefKeys.add("event:" + ev.getId());
            } else {
                eventGroupRepository.deleteByEventIdAndGroupId(ev.getId(), groupId);
            }
        }

        // 2. 본인이 이 그룹에 만든 (남의 일정 기반) 그림자 — 기존 cleanupShadowsOnGroupExit 의 역할
        List<Long> remainingGroupEventIds = eventGroupRepository.findAllByGroupId(groupId).stream()
                .map(EventGroup::getEventId).toList();
        if (!remainingGroupEventIds.isEmpty()) {
            for (Event s : eventRepository.findByCreatedByAndShadowOfEventIdIn(userId, remainingGroupEventIds)) {
                eventGroupRepository.deleteAllByEventId(s.getId());
                eventRepository.delete(s);
                deletedEventIds.add(s.getId());
            }
        }

        // 2-1. (방향1) 위에서 삭제된 일정을 linkedEvent 로 가진 폴 → linkedEventId 리셋.
        //   탈퇴자가 "먼저 추가"했던 확정 일정이 삭제되면, 그 폴이 영영 "등록됨"으로 막혀 아무도
        //   재등록 못 하는 댕글링을 방지(확정은 유지 → 다른 멤버가 "내 일정에 추가" 재등록 가능).
        if (!deletedEventIds.isEmpty()) {
            pollRepository.findByLinkedEventIdIn(deletedEventIds).forEach(Poll::clearLinkedEvent);
        }

        // 3. 본인이 이 그룹에 만든 폴 (slots + votes 통째)
        List<Long> deletedPollIds = new ArrayList<>();
        for (Poll p : pollRepository.findByCreatedByAndGroupId(userId, groupId)) {
            pollVoteRepository.deleteAllByPollId(p.getId());
            pollSlotRepository.deleteAllByPollId(p.getId());
            pollRepository.delete(p);
            deletedPollIds.add(p.getId());
            staleRefKeys.add("poll:" + p.getId());
        }

        // 3-1. (방향2) 삭제된 폴에서 파생됐지만 다른 사람이 추가해 살아남는 일정 → fromPollId 끊기.
        //   탈퇴자 폴은 사라지지만 그 일정(다른 사람 createdBy)은 유지 → 죽은 폴 참조만 제거해 독립.
        if (!deletedPollIds.isEmpty()) {
            eventRepository.findByFromPollIdIn(deletedPollIds).forEach(Event::detachFromPoll);
        }

        // 4. 본인의 이 그룹 폴 응답 (남이 만든 폴들)
        for (Poll p : pollRepository.findAllByGroupId(groupId)) {
            pollVoteRepository.deleteByPollIdAndUserId(p.getId(), userId);
        }

        // 5. 본인이 이 그룹에 올린 사진 — DB 행은 지금, S3 파일은 커밋 후 (P1-5: 풀 점유/롤백 정합)
        List<String> staleKeys = new ArrayList<>();
        for (Photo photo : photoRepository.findAllByUploadedByAndGroupId(userId, groupId)) {
            staleKeys.add(photo.getStorageKey());
            photoRepository.delete(photo);
        }
        StorageCleanup.deleteAfterCommit(photoStorage, staleKeys);

        // 6. 위에서 하드 삭제된 poll/event 의 죽은 참조 알림을 모든 수신자에서 제거(무한로딩 근본 차단).
        notificationService.deleteByRefKeys(staleRefKeys);

        // 7. 이 멤버가 받은 group:/photo: 알림(이 그룹 관련)도 정리 — 떠난 그룹이라 더는 볼 수 없는 죽은 참조.
        //    그룹은 살아있을 수 있으므로(다른 멤버에겐 유효) 전역 아닌 '이 수신자' 것만 삭제(엣지 감사 P2-1).
        //    group delete(:201) 는 멤버별로 이 메서드를 돌므로 전원 정리됨.
        notificationService.deleteForUserByRefKeys(userId, List.of("group:" + groupId, "photo:" + groupId));
    }

    @Transactional
    public InviteResponse issueInvite(Long userId, Long groupId) {
        Group group = loadGroupAndAssertMember(userId, groupId);
        if (group.isPersonal()) {
            throw new CustomException(ErrorCode.PERSONAL_GROUP_NOT_INVITABLE);
        }
        // invitePolicy 체크 — OWNER_ONLY 면 owner 만
        if (group.getInvitePolicy() == com.gatherit.api.group.entity.GroupMemberPolicy.OWNER_ONLY
                && !group.isOwner(userId)) {
            throw new CustomException(ErrorCode.GROUP_INVITE_FORBIDDEN);
        }
        InviteTokenStore.Issued issued = inviteTokenStore.issue(groupId);
        return new InviteResponse(issued.token(), issued.expiresAt());
    }

    /**
     * 초대 토큰 미리보기 — 가입 전 그룹 정보 + 이미 멤버인지 여부 반환.
     * FE 가 "이 그룹에 참여할까요?" 확인 모달에 사용.
     */
    @Transactional(readOnly = true)
    public GroupPreviewResponse previewByToken(Long userId, String token) {
        Long groupId = inviteTokenStore.validate(token)
                .orElseThrow(() -> new CustomException(ErrorCode.INVITE_TOKEN_INVALID));
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        long memberCount = memberRepository.countByGroupId(groupId);
        boolean alreadyMember = memberRepository.existsByGroupIdAndUserId(groupId, userId);
        return new GroupPreviewResponse(group.getId(), group.getName(), group.getColorKey(), memberCount, alreadyMember);
    }

    @Transactional
    public GroupResponse joinByToken(Long userId, String token) {
        Long groupId = inviteTokenStore.validate(token)
                .orElseThrow(() -> new CustomException(ErrorCode.INVITE_TOKEN_INVALID));
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new CustomException(ErrorCode.ALREADY_GROUP_MEMBER);
        }
        return addMemberAndMigrateLinkedPolls(userId, group);
    }

    /** 연결한 웹 투표를 근거로 직접 가입. 그룹 설정이 꺼져 있으면 정식 초대 링크가 필요하다. */
    @Transactional
    public GroupResponse joinFromLinkedPoll(Long userId, Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (group.isPersonal()) {
            throw new CustomException(ErrorCode.PERSONAL_GROUP_NOT_INVITABLE);
        }

        var existing = memberRepository.findByGroupIdAndUserId(groupId, userId);
        if (existing.isPresent()) {
            migrateLinkedPollVotes(userId, groupId);
            return GroupResponse.of(
                    group,
                    memberRepository.countByGroupId(groupId),
                    existing.get().getColorKey());
        }
        if (!group.isPublicPollJoinAllowed()) {
            throw new CustomException(ErrorCode.GROUP_PUBLIC_POLL_JOIN_FORBIDDEN);
        }
        return addMemberAndMigrateLinkedPolls(userId, group);
    }

    private GroupResponse addMemberAndMigrateLinkedPolls(Long userId, Group group) {
        Long groupId = group.getId();
        GroupColor autoColor = pickUnusedColorForUser(userId);
        memberRepository.save(GroupMember.builder()
                .groupId(groupId)
                .userId(userId)
                .role(MemberRole.MEMBER)
                .colorKey(autoColor)
                .build());

        // 웹에서 먼저 응답했다면 게스트 응답을 정식 멤버 응답으로 옮겨 중복 집계를 막는다.
        migrateLinkedPollVotes(userId, groupId);

        // GROUP_JOINED_BY — 그룹장에게만 push (다수 멤버에게 보내면 시끄러움). 그룹 mute 면 차단.
        if (!group.getCreatedBy().equals(userId)) {
            String joinerName = userRepository.findById(userId).map(User::getNickname).orElse("새 멤버");
            notificationService.pushScoped(
                    group.getCreatedBy(),
                    NotificationType.GROUP_JOINED_BY,
                    "%s님이 %s에 참여했어요".formatted(joinerName, group.getName()),
                    null,
                    "group:" + groupId,
                    java.util.List.of(groupId)
            );
        }

        return GroupResponse.of(group, memberRepository.countByGroupId(groupId), autoColor);
    }

    private void migrateLinkedPollVotes(Long userId, Long groupId) {
        List<PollGuestParticipant> participants =
                pollGuestParticipantRepository.findAllLinkedByUserIdAndGroupId(userId, groupId);
        for (PollGuestParticipant participant : participants) {
            Long pollId = participant.getPollId();
            Set<Long> availableSlotIds = new HashSet<>(pollGuestVoteRepository
                    .findAllByParticipantId(participant.getId()).stream()
                    .map(vote -> vote.getSlotId())
                    .toList());
            List<PollSlot> slots = pollSlotRepository.findAllByPollIdOrderByStartAtAsc(pollId);

            for (PollSlot slot : slots) {
                PollVote.PK key = new PollVote.PK(pollId, slot.getId(), userId);
                boolean available = availableSlotIds.contains(slot.getId());
                pollVoteRepository.findById(key).ifPresentOrElse(
                        existing -> existing.setAvailable(available),
                        () -> pollVoteRepository.save(PollVote.builder()
                                .pollId(pollId)
                                .slotId(slot.getId())
                                .userId(userId)
                                .available(available)
                                .build())
                );
            }

            pollGuestVoteRepository.deleteAllByParticipantId(participant.getId());
            pollGuestParticipantRepository.delete(participant);
        }
    }

    /**
     * 본인의 그룹 색만 변경 — 다른 사람한테 영향 X. 개인 캘린더에도 적용.
     */
    @Transactional
    public GroupResponse updateMyColor(Long userId, Long groupId, GroupColor colorKey) {
        GroupMember gm = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_GROUP_MEMBER));
        gm.recolor(colorKey);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        return GroupResponse.of(group, memberRepository.countByGroupId(groupId), colorKey);
    }

    // ─── helpers ─────────────────────────────────────────────

    private Group loadGroupAndAssertMember(Long userId, Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
        }
        return group;
    }

    private Group loadGroupAndAssertOwner(Long userId, Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (!group.isOwner(userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_OWNER);
        }
        return group;
    }
}

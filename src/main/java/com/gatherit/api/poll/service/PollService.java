package com.gatherit.api.poll.service;

import com.gatherit.api.event.entity.Event;
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
import com.gatherit.api.poll.dto.PollCreateRequest;
import com.gatherit.api.poll.dto.PollResponse;
import com.gatherit.api.poll.dto.PollUpdateRequest;
import com.gatherit.api.poll.dto.VoteRequest;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollGuestParticipant;
import com.gatherit.api.poll.entity.PollGuestVote;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.PollVote;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.poll.repository.PollGuestVoteRepository;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.poll.repository.PollSlotRepository;
import com.gatherit.api.poll.repository.PollVoteRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final EventRepository eventRepository;
    private final PollSlotRepository slotRepository;
    private final PollVoteRepository voteRepository;
    private final PollGuestParticipantRepository guestParticipantRepository;
    private final PollGuestVoteRepository guestVoteRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final NotificationService notificationService;

    @Transactional
    public PollResponse create(Long userId, PollCreateRequest req) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(req.groupId(), userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
        }
        Group group = groupRepository.findById(req.groupId())
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (group.isPersonal()) {
            throw new CustomException(ErrorCode.PERSONAL_GROUP_NO_POLL);
        }
        if (group.getPollCreatePolicy() == com.gatherit.api.group.entity.GroupMemberPolicy.OWNER_ONLY
                && !group.isOwner(userId)) {
            throw new CustomException(ErrorCode.GROUP_POLL_CREATE_FORBIDDEN);
        }
        Poll poll = Poll.builder()
                .groupId(req.groupId())
                .title(req.title())
                .deadlineAt(req.deadlineAt())
                .confirmPolicy(req.confirmPolicy())
                .createdBy(userId)
                .build();
        pollRepository.save(poll);

        for (PollCreateRequest.SlotInput si : req.slots()) {
            slotRepository.save(PollSlot.builder()
                    .pollId(poll.getId())
                    .startAt(si.startAt())
                    .endAt(si.endAt())
                    .build());
        }

        // POLL_CREATED — 그룹원(발의자 제외) 에게 push. 그룹 mute 시 차단.
        String title = "새 투표가 시작됐어요";
        String subtitle = "%s - %s".formatted(group.getName(), poll.getTitle());
        String refKey = "poll:" + poll.getId();
        java.util.List<Long> groupCtx = java.util.List.of(req.groupId());
        java.util.List<Long> pollTargets = groupMemberRepository.findAllByGroupId(req.groupId()).stream()
                .map(GroupMember::getUserId).filter(uid -> !uid.equals(userId)).toList();
        notificationService.pushScopedToUsers(pollTargets, NotificationType.POLL_CREATED, title, subtitle, refKey, groupCtx);

        return buildDetail(userId, poll.getId());
    }

    @Transactional(readOnly = true)
    public PollResponse getDetail(Long userId, Long pollId) {
        return buildDetail(userId, pollId);
    }

    /** 내가 아직 응답 안 한 진행 중 투표 수 — 잠금화면 라이브 카드 "진행 중인 투표" 알림 줄용. */
    @Transactional(readOnly = true)
    public long countPending(Long userId) {
        return pollRepository.countPendingForUser(userId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<PollResponse> findByGroup(Long userId, Long groupId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
        }
        List<Poll> polls = pollRepository.findAllByGroupIdOrderByCreatedAtDesc(groupId);
        if (polls.isEmpty()) return List.of();

        // 연결 ID는 직접 삭제 후에도 tombstone 으로 남는다. 기존의 끊어진 연결까지
        // 한 번의 조회로 분류해 그룹 투표 목록에서 "삭제됨"을 정확히 표시한다.
        Set<Long> linkedEventIds = new HashSet<>();
        for (Poll p : polls) {
            if (p.getLinkedEventId() != null) linkedEventIds.add(p.getLinkedEventId());
        }
        Set<Long> existingEventIds = new HashSet<>();
        if (!linkedEventIds.isEmpty()) {
            for (Event event : eventRepository.findAllById(linkedEventIds)) {
                existingEventIds.add(event.getId());
            }
        }

        // 폴별 5쿼리(N+1) 대신 IN-query 일괄 선조회 (P2-9). 멤버십 검증/멤버수는 같은 그룹이라 1회면 충분.
        List<Long> pollIds = polls.stream().map(Poll::getId).toList();
        Map<Long, List<PollSlot>> slotsByPoll = new HashMap<>();
        for (PollSlot slot : slotRepository.findAllByPollIdInOrderByStartAtAsc(pollIds)) {
            slotsByPoll.computeIfAbsent(slot.getPollId(), k -> new ArrayList<>()).add(slot);
        }
        Map<Long, List<PollVote>> votesByPoll = new HashMap<>();
        for (PollVote v : voteRepository.findAllByPollIdIn(pollIds)) {
            votesByPoll.computeIfAbsent(v.getPollId(), k -> new ArrayList<>()).add(v);
        }
        Map<Long, List<PollGuestParticipant>> guestsByPoll = new HashMap<>();
        for (PollGuestParticipant participant : guestParticipantRepository.findAllByPollIdIn(pollIds)) {
            guestsByPoll.computeIfAbsent(participant.getPollId(), k -> new ArrayList<>()).add(participant);
        }
        Map<Long, List<PollGuestVote>> guestVotesByPoll = new HashMap<>();
        for (PollGuestVote vote : guestVoteRepository.findAllByPollIdIn(pollIds)) {
            guestVotesByPoll.computeIfAbsent(vote.getPollId(), k -> new ArrayList<>()).add(vote);
        }
        int totalMembers = (int) groupMemberRepository.countByGroupId(groupId);

        return polls.stream()
                .map(p -> assemble(p,
                        slotsByPoll.getOrDefault(p.getId(), List.of()),
                        votesByPoll.getOrDefault(p.getId(), List.of()),
                        guestsByPoll.getOrDefault(p.getId(), List.of()),
                        guestVotesByPoll.getOrDefault(p.getId(), List.of()),
                        totalMembers,
                        p.getLinkedEventId() != null && !existingEventIds.contains(p.getLinkedEventId())))
                .toList();
    }

    /**
     * 본인의 투표 전체 취소 — PollVote 행 자체를 삭제해서 "응답한 적 없는" 상태로 되돌림.
     * (모든 슬롯에 available=false 로 저장하는 vote() 와 다름 — 그건 "다 안 됨" 응답으로 카운트됨)
     */
    @Transactional
    public void cancelMyVote(Long userId, Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        assertGroupMember(poll, userId);
        if (poll.isConfirmed()) {
            throw new CustomException(ErrorCode.POLL_ALREADY_CONFIRMED);
        }
        voteRepository.deleteByPollIdAndUserId(pollId, userId);
    }

    @Transactional
    public void vote(Long userId, Long pollId, VoteRequest req) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        assertGroupMember(poll, userId);
        if (poll.isConfirmed()) {
            throw new CustomException(ErrorCode.POLL_ALREADY_CONFIRMED);
        }
        if (poll.isDeadlinePassed(Instant.now())) {
            throw new CustomException(ErrorCode.POLL_DEADLINE_PASSED);
        }

        List<PollSlot> slots = slotRepository.findAllByPollIdOrderByStartAtAsc(pollId);
        Set<Long> validSlotIds = new HashSet<>();
        for (PollSlot s : slots) validSlotIds.add(s.getId());

        for (VoteRequest.SlotVote sv : req.votes()) {
            if (!validSlotIds.contains(sv.slotId())) {
                throw new CustomException(ErrorCode.POLL_SLOT_NOT_FOUND);
            }
            PollVote.PK key = new PollVote.PK(pollId, sv.slotId(), userId);
            voteRepository.findById(key).ifPresentOrElse(
                    existing -> existing.setAvailable(sv.available()),
                    () -> voteRepository.save(PollVote.builder()
                            .pollId(pollId).slotId(sv.slotId()).userId(userId).available(sv.available())
                            .build())
            );
        }
    }

    /**
     * 투표 확정 — 동시 confirm race condition 처리.
     * @Version 낙관적 락. 두 명이 동시에 다른 slot 으로 confirm 클릭 시 한 명만 성공.
     * 두 번째 호출은 ObjectOptimisticLockingFailureException → POLL_CONFIRM_RACE 로 변환.
     */
    @Transactional
    public PollResponse confirm(Long userId, Long pollId, ConfirmRequest req) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        assertGroupMember(poll, userId);

        if (poll.getConfirmPolicy() == ConfirmPolicy.PROPOSER_ONLY && !poll.isProposer(userId)) {
            throw new CustomException(ErrorCode.POLL_NOT_PROPOSER);
        }
        if (poll.isConfirmed()) {
            throw new CustomException(ErrorCode.POLL_ALREADY_CONFIRMED);
        }

        // slot 검증
        PollSlot slot = slotRepository.findById(req.slotId())
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_SLOT_NOT_FOUND));
        if (!slot.getPollId().equals(pollId)) {
            throw new CustomException(ErrorCode.POLL_SLOT_NOT_FOUND);
        }

        poll.confirm(req.slotId());
        try {
            pollRepository.saveAndFlush(poll);  // 즉시 flush — 락 충돌 트리거
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
            log.warn("Poll confirm race condition pollId={} userId={}", pollId, userId);
            throw new CustomException(ErrorCode.POLL_CONFIRM_RACE);
        }

        // POLL_CONFIRMED 알림 — 응답한 유저들 (확정자 제외) 에게. 응답자 0명이면 그룹원 전원.
        Group group = groupRepository.findById(poll.getGroupId())
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        Set<Long> respondents = new HashSet<>(voteRepository.findRespondentUserIds(pollId));
        Set<Long> targets;
        if (respondents.isEmpty()) {
            targets = new HashSet<>();
            for (GroupMember gm : groupMemberRepository.findAllByGroupId(poll.getGroupId())) {
                targets.add(gm.getUserId());
            }
        } else {
            targets = respondents;
        }
        targets.remove(userId);
        String title = "%s 투표가 확정됐어요".formatted(poll.getTitle());
        String subtitle = group.getName();
        String refKey = "poll:" + pollId;
        java.util.List<Long> confirmCtx = java.util.List.of(poll.getGroupId());
        notificationService.pushScopedToUsers(targets, NotificationType.POLL_CONFIRMED, title, subtitle, refKey, confirmCtx);

        // 발의자가 확정자 본인이 아니면 — 내가 만든 투표가 확정됐음을 별도 알림 (POLL_COMPLETED, 전역 prefs).
        if (!poll.isProposer(userId)) {
            String pTitle = "%s 투표가 확정됐어요".formatted(poll.getTitle());
            notificationService.pushPollCompleted(poll.getCreatedBy(), pTitle, group.getName(), refKey);
        }

        // 확정 = 날짜 잠그기만. 일정 추가는 사용자가 직접 — FE 의 "내 일정에 추가하기" 로 분리.
        return buildDetail(userId, pollId);
    }

    /**
     * 투표 수정 — 발의자만, 미확정 상태에서만 가능.
     * 슬롯은 변경 불가 (이미 투표한 사람들이 있을 수 있음). title/deadline/confirmPolicy 만.
     */
    @Transactional
    public PollResponse update(Long userId, Long pollId, PollUpdateRequest req) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        if (!poll.isProposer(userId)) {
            throw new CustomException(ErrorCode.POLL_NOT_PROPOSER);
        }
        if (poll.isConfirmed()) {
            throw new CustomException(ErrorCode.POLL_ALREADY_CONFIRMED);
        }
        poll.editBasic(req.title(), req.deadlineAt(), req.confirmPolicy());
        return buildDetail(userId, pollId);
    }

    @Transactional
    public void delete(Long userId, Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        if (!poll.isProposer(userId)) {
            throw new CustomException(ErrorCode.POLL_NOT_PROPOSER);
        }
        voteRepository.deleteAllByPollId(pollId);
        slotRepository.deleteAllByPollId(pollId);
        pollRepository.delete(poll);
        // 죽은 참조 알림 정리 — 투표가 전역 삭제됐으므로 모든 수신자의 poll:{id} 알림 제거(무한로딩/유령탭 차단).
        // v1.3.1 은 그룹탈퇴/계정탈퇴 경로만 정리, 발의자 직접 삭제(흔한 경로)는 빠져 있었음(엣지 감사 P2-1).
        notificationService.deleteByRefKeys(java.util.List.of("poll:" + pollId));
    }

    /**
     * 마감 도달 알림 — deadline 지났는데 미확정인 폴의 발의자에게 "마감됐어요, 확정해주세요" 1회 push.
     * deadline_notified 플래그로 중복 방지. 스케줄러(5분 주기)에서 호출.
     */
    @Transactional
    public void notifyDeadlineReachedPolls() {
        Instant now = Instant.now();
        List<Poll> due = pollRepository.findAllByConfirmedSlotIdIsNullAndDeadlineNotifiedFalseAndDeadlineAtBefore(now);
        for (Poll poll : due) {
            try {
                Group group = groupRepository.findById(poll.getGroupId()).orElse(null);
                String groupName = group != null ? group.getName() : "";
                String title = "%s 투표가 마감됐어요".formatted(poll.getTitle());
                String subtitle = groupName + " - 날짜를 확정해주세요";
                notificationService.pushPollCompleted(poll.getCreatedBy(), title, subtitle, "poll:" + poll.getId());
                poll.markDeadlineNotified();
            } catch (Exception e) {
                log.error("Deadline notify failed for poll {}", poll.getId(), e);
            }
        }
    }

    /**
     * Dead poll cleanup — 마지막 슬롯의 endAt 마저 지났는데 아직 미확정인 폴은 의미 없음 → 삭제.
     *
     * 자동 확정은 하지 않음. deadline 만 지난 상태(슬롯 날짜는 미래)는 그대로 둬서
     * 권한자(발의자/모두)가 수동으로 확정할 수 있도록 함.
     */
    @Transactional
    public void cleanupDeadPolls() {
        Instant now = Instant.now();
        List<Poll> unconfirmed = pollRepository.findAllByConfirmedSlotIdIsNull();
        for (Poll poll : unconfirmed) {
            try {
                List<PollSlot> slots = slotRepository.findAllByPollIdOrderByStartAtAsc(poll.getId());
                if (slots.isEmpty()) continue;
                PollSlot last = slots.get(slots.size() - 1);
                if (last.getEndAt().isBefore(now)) {
                    voteRepository.deleteAllByPollId(poll.getId());
                    slotRepository.deleteAllByPollId(poll.getId());
                    pollRepository.delete(poll);
                    log.info("Deleted dead poll {} (last slot end < now)", poll.getId());
                }
            } catch (Exception e) {
                log.error("Cleanup failed for poll {}", poll.getId(), e);
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────

    private void assertGroupMember(Poll poll, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(poll.getGroupId(), userId)) {
            throw new CustomException(ErrorCode.POLL_FORBIDDEN);
        }
    }

    private PollResponse buildDetail(Long userId, Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        assertGroupMember(poll, userId);

        List<PollSlot> slots = slotRepository.findAllByPollIdOrderByStartAtAsc(pollId);
        List<PollVote> votes = voteRepository.findAllByPollId(pollId);
        List<PollGuestParticipant> guests = guestParticipantRepository.findAllByPollId(pollId);
        List<PollGuestVote> guestVotes = guestVoteRepository.findAllByPollId(pollId);
        int totalMembers = (int) groupMemberRepository.countByGroupId(poll.getGroupId());
        boolean linkedEventDeleted = poll.getLinkedEventId() != null
                && !eventRepository.existsById(poll.getLinkedEventId());
        return assemble(poll, slots, votes, guests, guestVotes, totalMembers, linkedEventDeleted);
    }

    /** slots/votes 가 이미 메모리에 있을 때의 응답 조립 — 단건(buildDetail)과 목록(findByGroup)이 공유. */
    private PollResponse assemble(Poll poll, List<PollSlot> slots, List<PollVote> votes,
                                  List<PollGuestParticipant> guests,
                                  List<PollGuestVote> guestVotes,
                                  int totalMembers, boolean linkedEventDeleted) {
        Map<Long, List<Long>> availableUsersBySlot = new HashMap<>();
        Set<Long> respondents = new HashSet<>();
        for (PollVote v : votes) {
            respondents.add(v.getUserId());
            if (v.isAvailable()) {
                availableUsersBySlot.computeIfAbsent(v.getSlotId(), k -> new ArrayList<>()).add(v.getUserId());
            }
        }

        Map<Long, List<String>> availableGuestsBySlot = new HashMap<>();
        for (PollGuestVote vote : guestVotes) {
            availableGuestsBySlot.computeIfAbsent(vote.getSlotId(), k -> new ArrayList<>())
                    .add(vote.getParticipant().getNickname());
        }
        List<String> guestNames = guests.stream()
                .map(PollGuestParticipant::getNickname)
                .toList();

        List<PollResponse.SlotResult> slotResults = slots.stream()
                .map(s -> {
                    List<Long> memberIds = availableUsersBySlot.getOrDefault(s.getId(), List.of());
                    List<String> guestNicknames = availableGuestsBySlot.getOrDefault(s.getId(), List.of());
                    return new PollResponse.SlotResult(
                            s.getId(), s.getStartAt(), s.getEndAt(),
                            memberIds.size() + guestNicknames.size(), memberIds, guestNicknames
                    );
                }).toList();

        return PollResponse.of(poll, linkedEventDeleted, slotResults,
                respondents.size() + guests.size(), totalMembers, guestNames);
    }

}

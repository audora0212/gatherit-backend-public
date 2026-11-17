package com.gatherit.api.poll.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.dto.GroupResponse;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.poll.dto.*;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollGuestParticipant;
import com.gatherit.api.poll.entity.PollGuestVote;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.poll.entity.PollVote;
import com.gatherit.api.poll.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PublicPollService {

    private static final String CONNECTION_CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PollRepository pollRepository;
    private final PollSlotRepository slotRepository;
    private final PollVoteRepository memberVoteRepository;
    private final PollGuestParticipantRepository participantRepository;
    private final PollGuestVoteRepository guestVoteRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupService groupService;

    @Value("${gatherit.public-web-base-url:https://gatherit.co.kr}")
    private String publicWebBaseUrl;

    @Value("${gatherit.app-deep-link-base:gatherit://public-poll}")
    private String appDeepLinkBase;

    @Transactional
    public PollShareResponse issueShare(Long userId, Long pollId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
        if (!poll.isProposer(userId)) {
            throw new CustomException(ErrorCode.POLL_NOT_PROPOSER);
        }

        Instant now = Instant.now();
        if (!poll.hasActivePublicShare(now)) {
            Instant resultWindowStart = poll.getDeadlineAt().isAfter(now) ? poll.getDeadlineAt() : now;
            poll.enablePublicShare(generateUniqueShareToken(), resultWindowStart.plus(7, ChronoUnit.DAYS));
        }

        String base = publicWebBaseUrl.replaceAll("/+$", "");
        String url = base + "/p/" + poll.getPublicShareToken();
        return new PollShareResponse(poll.getPublicShareToken(), url, poll.getPublicShareExpiresAt());
    }

    @Transactional(readOnly = true)
    public PublicPollResponse getPublicPoll(String shareToken, String participantToken) {
        Poll poll = resolveActiveShare(shareToken);
        return buildResponse(poll, findParticipantOrNull(poll.getId(), participantToken));
    }

    @Transactional
    public PublicPollVoteResponse vote(String shareToken, PublicPollVoteRequest request) {
        Poll poll = resolveActiveShare(shareToken);
        ensureVotingOpen(poll);

        String nickname = normalizeNickname(request.nickname());
        ParticipantSession session = resolveOrCreateParticipant(poll, nickname, request.participantToken());
        replaceVotes(poll, session.participant(), request.availableSlotIds());

        return new PublicPollVoteResponse(
                session.rawToken(),
                buildResponse(poll, session.participant())
        );
    }

    /** 웹 브라우저에 저장된 응답 세션을 앱으로 넘길 단기 연결 링크와 코드를 발급한다. */
    @Transactional
    public PublicPollHandoffResponse issueHandoff(
            String shareToken, PublicPollHandoffRequest request) {
        Poll poll = resolveActiveShare(shareToken);
        PollGuestParticipant participant = participantRepository
                .findByPollIdAndEditTokenHash(poll.getId(), hashToken(request.participantToken()))
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_GUEST_SESSION_INVALID));

        String rawCode = generateUniqueConnectionCode();
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        if (poll.getPublicShareExpiresAt().isBefore(expiresAt)) {
            expiresAt = poll.getPublicShareExpiresAt();
        }
        participant.issueHandoff(hashToken(rawCode), expiresAt);

        String base = appDeepLinkBase.replaceAll("[/?]+$", "");
        String appUrl = base + "?token=" + shareToken + "&handoff=" + rawCode;
        return new PublicPollHandoffResponse(appUrl, formatConnectionCode(rawCode), expiresAt);
    }

    /** 앱 로그인 뒤 단기 코드를 현재 계정에 연결한다. 재시도는 같은 계정에 한해 멱등이다. */
    @Transactional
    public PublicPollResponse claimLinkedPoll(
            Long userId, String shareToken, LinkedPollClaimRequest request) {
        // claim/재접근은 소유권 기준 — 공유 링크 만료와 무관(신규 claim 은 handoff 코드가 gate).
        Poll poll = resolvePollByToken(shareToken);

        Optional<PollGuestParticipant> alreadyClaimed = participantRepository
                .findByPollIdAndLinkedUserId(poll.getId(), userId);
        if (alreadyClaimed.isPresent()) {
            return buildResponse(poll, alreadyClaimed.get());
        }

        String normalizedCode = normalizeConnectionCode(request.handoffCode());
        String codeHash = hashToken(normalizedCode);
        PollGuestParticipant participant = participantRepository
                .findByPollIdAndHandoffCodeHash(poll.getId(), codeHash)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_HANDOFF_INVALID));
        if (!participant.hasActiveHandoff(codeHash, Instant.now())) {
            throw new CustomException(ErrorCode.POLL_HANDOFF_INVALID);
        }
        return claimParticipant(userId, poll, participant);
    }

    /** 앱 설치 과정에서 딥링크가 유실된 경우, 사람이 입력한 연결 코드만으로 응답을 찾는다. */
    @Transactional
    public PublicPollResponse claimLinkedPollByCode(
            Long userId, LinkedPollCodeClaimRequest request) {
        String normalizedCode = normalizeConnectionCode(request.connectionCode());
        String codeHash = hashToken(normalizedCode);
        PollGuestParticipant participant = participantRepository.findByHandoffCodeHash(codeHash)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_HANDOFF_INVALID));
        Poll poll = participant.getPoll();
        Instant now = Instant.now();
        if (!poll.hasActivePublicShare(now) || !participant.hasActiveHandoff(codeHash, now)) {
            throw new CustomException(ErrorCode.POLL_HANDOFF_INVALID);
        }
        return claimParticipant(userId, poll, participant);
    }

    @Transactional(readOnly = true)
    public List<PublicPollResponse> findLinkedPolls(Long userId) {
        // 공유 링크 만료 여부와 무관하게 노출 — 한 번 계정에 연결한 투표는 소유자의 것.
        // (수정 가능/불가는 투표 마감이 gate. 미가입 상태라도 자기 응답은 계속 조회/수정 가능.)
        return participantRepository.findAllByLinkedUserId(userId).stream()
                .map(participant -> buildResponse(participant.getPoll(), participant))
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicPollResponse getLinkedPoll(Long userId, String shareToken) {
        Poll poll = resolvePollByToken(shareToken);
        PollGuestParticipant participant = resolveLinkedParticipant(poll.getId(), userId);
        return buildResponse(poll, participant);
    }

    @Transactional
    public PublicPollResponse voteLinkedPoll(
            Long userId, String shareToken, LinkedPollVoteRequest request) {
        Poll poll = resolvePollByToken(shareToken);
        ensureVotingOpen(poll);
        PollGuestParticipant participant = resolveLinkedParticipant(poll.getId(), userId);
        String nickname = normalizeNickname(request.nickname());
        if (!participant.getNickname().equals(nickname)) {
            if (participantRepository.existsNicknameExcept(poll.getId(), nickname, participant.getId())) {
                throw new CustomException(ErrorCode.POLL_GUEST_NAME_TAKEN);
            }
            participant.rename(nickname);
        }
        replaceVotes(poll, participant, request.availableSlotIds());
        return buildResponse(poll, participant);
    }

    /** 연결된 웹 투표를 근거로 그룹에 직접 가입하고 응답을 정식 멤버 투표로 병합한다. */
    @Transactional
    public GroupResponse joinLinkedPollGroup(Long userId, String shareToken) {
        Poll poll = resolvePollByToken(shareToken);
        resolveLinkedParticipant(poll.getId(), userId);
        return groupService.joinFromLinkedPoll(userId, poll.getGroupId());
    }

    private ParticipantSession resolveOrCreateParticipant(Poll poll, String nickname, String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            PollGuestParticipant participant = participantRepository
                    .findByPollIdAndEditTokenHash(poll.getId(), hashToken(rawToken))
                    .orElseThrow(() -> new CustomException(ErrorCode.POLL_GUEST_SESSION_INVALID));
            if (!participant.getNickname().equals(nickname)) {
                if (participantRepository.existsNicknameExcept(poll.getId(), nickname, participant.getId())) {
                    throw new CustomException(ErrorCode.POLL_GUEST_NAME_TAKEN);
                }
                participant.rename(nickname);
            }
            return new ParticipantSession(participant, rawToken);
        }

        if (participantRepository.existsNickname(poll.getId(), nickname)) {
            throw new CustomException(ErrorCode.POLL_GUEST_NAME_TAKEN);
        }
        String newRawToken = generateSecret();
        PollGuestParticipant participant = PollGuestParticipant.builder()
                .poll(poll)
                .nickname(nickname)
                .editTokenHash(hashToken(newRawToken))
                .build();
        try {
            participantRepository.saveAndFlush(participant);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.POLL_GUEST_NAME_TAKEN);
        }
        return new ParticipantSession(participant, newRawToken);
    }

    private PublicPollResponse buildResponse(Poll poll, PollGuestParticipant me) {
        Group group = groupRepository.findById(poll.getGroupId())
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_SHARE_INVALID));
        boolean groupMember = me != null
                && me.getLinkedUserId() != null
                && groupMemberRepository.existsByGroupIdAndUserId(group.getId(), me.getLinkedUserId());
        List<PollSlot> slots = slotRepository.findAllByPollIdOrderByStartAtAsc(poll.getId());
        List<PollVote> memberVotes = memberVoteRepository.findAllByPollId(poll.getId());
        List<PollGuestParticipant> guests = participantRepository.findAllByPollId(poll.getId());
        List<PollGuestVote> guestVotes = guestVoteRepository.findAllByPollId(poll.getId());

        Set<Long> memberRespondents = new HashSet<>();
        Map<Long, Integer> memberAvailable = new HashMap<>();
        for (PollVote vote : memberVotes) {
            memberRespondents.add(vote.getUserId());
            if (vote.isAvailable()) memberAvailable.merge(vote.getSlotId(), 1, Integer::sum);
        }

        Map<Long, List<String>> guestNamesBySlot = new HashMap<>();
        for (PollGuestVote vote : guestVotes) {
            guestNamesBySlot.computeIfAbsent(vote.getSlotId(), ignored -> new ArrayList<>())
                    .add(vote.getParticipant().getNickname());
        }
        List<Long> myAvailable = me == null ? List.of() : guestVotes.stream()
                .filter(v -> v.getParticipant().getId().equals(me.getId()))
                .map(PollGuestVote::getSlotId)
                .toList();

        List<PublicPollResponse.PublicSlot> publicSlots = slots.stream()
                .map(slot -> {
                    List<String> guestNames = guestNamesBySlot.getOrDefault(slot.getId(), List.of());
                    return new PublicPollResponse.PublicSlot(
                            slot.getId(), slot.getStartAt(), slot.getEndAt(),
                            memberAvailable.getOrDefault(slot.getId(), 0) + guestNames.size(),
                            guestNames
                    );
                })
                .toList();

        List<String> guestNames = guests.stream().map(PollGuestParticipant::getNickname).toList();
        boolean closed = poll.isConfirmed() || poll.isDeadlinePassed(Instant.now());
        return new PublicPollResponse(
                poll.getId(), poll.getPublicShareToken(), group.getId(), group.getName(),
                groupMember, group.isPublicPollJoinAllowed(), poll.getTitle(),
                poll.getDeadlineAt(), poll.getConfirmedSlotId(),
                closed, poll.getPublicShareExpiresAt(), memberRespondents.size() + guests.size(),
                memberRespondents.size(), guestNames,
                me == null ? null : me.getNickname(), myAvailable, publicSlots
        );
    }

    private PollGuestParticipant resolveLinkedParticipant(Long pollId, Long userId) {
        return participantRepository.findByPollIdAndLinkedUserId(pollId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_LINKED_ACCESS_DENIED));
    }

    private PublicPollResponse claimParticipant(
            Long userId, Poll poll, PollGuestParticipant participant) {
        if (participant.getLinkedUserId() != null) {
            if (!participant.getLinkedUserId().equals(userId)) {
                throw new CustomException(ErrorCode.POLL_GUEST_ALREADY_LINKED);
            }
            return buildResponse(poll, participant);
        }
        if (participantRepository.findByPollIdAndLinkedUserId(poll.getId(), userId).isPresent()) {
            throw new CustomException(ErrorCode.POLL_GUEST_ALREADY_LINKED);
        }

        participant.claim(userId);
        try {
            participantRepository.saveAndFlush(participant);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.POLL_GUEST_ALREADY_LINKED);
        }
        return buildResponse(poll, participant);
    }

    private void ensureVotingOpen(Poll poll) {
        if (poll.isConfirmed()) throw new CustomException(ErrorCode.POLL_ALREADY_CONFIRMED);
        if (poll.isDeadlinePassed(Instant.now())) {
            throw new CustomException(ErrorCode.POLL_DEADLINE_PASSED);
        }
    }

    private void replaceVotes(
            Poll poll, PollGuestParticipant participant, Set<Long> availableSlotIds) {
        List<PollSlot> slots = slotRepository.findAllByPollIdOrderByStartAtAsc(poll.getId());
        Set<Long> validSlotIds = new HashSet<>();
        for (PollSlot slot : slots) validSlotIds.add(slot.getId());
        if (!validSlotIds.containsAll(availableSlotIds)) {
            throw new CustomException(ErrorCode.POLL_SLOT_NOT_FOUND);
        }

        guestVoteRepository.deleteAllByParticipantId(participant.getId());
        if (!availableSlotIds.isEmpty()) {
            List<PollGuestVote> votes = availableSlotIds.stream()
                    .map(slotId -> PollGuestVote.builder()
                            .participant(participant)
                            .slotId(slotId)
                            .build())
                    .toList();
            guestVoteRepository.saveAll(votes);
        }
    }

    /** 토큰 형식 검증 + 폴 조회만. 공유 활성 여부는 확인하지 않는다. */
    private Poll resolvePollByToken(String shareToken) {
        if (shareToken == null || !shareToken.matches("^[a-f0-9]{32}$")) {
            throw new CustomException(ErrorCode.POLL_SHARE_INVALID);
        }
        return pollRepository.findByPublicShareToken(shareToken)
                .orElseThrow(() -> new CustomException(ErrorCode.POLL_SHARE_INVALID));
    }

    /**
     * PUBLIC(비회원 게스트) 접근용 — 공유 링크가 활성(미만료)이어야 한다.
     * 이미 계정에 연결된(linked) 접근은 {@link #resolvePollByToken} 를 쓴다:
     * claim = 응답 소유권 복구이므로, 공유 링크가 만료돼도 소유자는 자기 응답을 계속 보고 수정할 수 있다.
     */
    private Poll resolveActiveShare(String shareToken) {
        Poll poll = resolvePollByToken(shareToken);
        if (!poll.hasActivePublicShare(Instant.now())) {
            throw new CustomException(ErrorCode.POLL_SHARE_INVALID);
        }
        return poll;
    }

    private PollGuestParticipant findParticipantOrNull(Long pollId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        return participantRepository.findByPollIdAndEditTokenHash(pollId, hashToken(rawToken)).orElse(null);
    }

    private String generateUniqueShareToken() {
        for (int i = 0; i < 5; i++) {
            String token = generateSecret();
            if (!pollRepository.existsByPublicShareToken(token)) return token;
        }
        throw new IllegalStateException("Failed to generate unique public poll token");
    }

    private String generateSecret() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String generateUniqueConnectionCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder code = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                code.append(CONNECTION_CODE_ALPHABET.charAt(
                        SECURE_RANDOM.nextInt(CONNECTION_CODE_ALPHABET.length())));
            }
            String raw = code.toString();
            if (!participantRepository.existsByHandoffCodeHash(hashToken(raw))) return raw;
        }
        throw new IllegalStateException("Failed to generate unique poll connection code");
    }

    private String formatConnectionCode(String rawCode) {
        if (rawCode.length() != 8) return rawCode;
        return rawCode.substring(0, 4) + "-" + rawCode.substring(4);
    }

    private String normalizeConnectionCode(String code) {
        String compact = code.trim().replace("-", "").replace(" ", "");
        if (compact.matches("^[a-f0-9]{32}$")) return compact;
        return compact.toUpperCase(Locale.ROOT);
    }

    private String normalizeNickname(String nickname) {
        return nickname.trim().replaceAll("\\s+", " ");
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ParticipantSession(PollGuestParticipant participant, String rawToken) {}
}

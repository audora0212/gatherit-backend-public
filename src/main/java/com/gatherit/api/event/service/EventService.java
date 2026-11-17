package com.gatherit.api.event.service;

import com.gatherit.api.event.dto.EditScope;
import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.dto.EventUpdateRequest;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.entity.DdayPin;
import com.gatherit.api.event.repository.DdayPinRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.repository.PollRepository;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final DdayPinRepository ddayPinRepository;
    private final EventGroupRepository eventGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final PollRepository pollRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    /** 생성자 닉네임 + 본인 색을 한 번 조회해 채운다. 단건용 (create/update/getDetail). */
    private EventResponse enrichCreator(EventResponse r, Long creatorId) {
        if (creatorId == null) return r;
        return userRepository.findById(creatorId)
                .map(u -> r.withCreator(u.getNickname(), u.getPersonalColor()))
                .orElse(r);
    }

    private static final DateTimeFormatter EVENT_TIME_FMT =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    @Transactional
    public EventResponse create(Long userId, EventCreateRequest req) {
        return create(userId, req, true);
    }

    /** notify=false 면 EVENT_CREATED 개별 알림을 생략(AI 일괄 등록에서 묶음 알림으로 대체). */
    @Transactional
    public EventResponse create(Long userId, EventCreateRequest req, boolean notify) {
        if (req.shadowOfEventId() == null && req.endAt().isBefore(req.startAt())) {
            throw new CustomException(ErrorCode.EVENT_TIME_INVALID);
        }
        if (req.groupIds().isEmpty()) {
            throw new CustomException(ErrorCode.EVENT_NO_GROUPS);
        }
        // 모든 groupIds 가 user 의 멤버십 + eventCreate 권한 안에 있는지 검증.
        // 그림자도 결국 그 그룹에 일정 데이터 추가하는 거라 동일 정책 적용.
        for (Long gid : req.groupIds()) {
            if (!groupMemberRepository.existsByGroupIdAndUserId(gid, userId)) {
                throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
            }
            Group g = groupRepository.findById(gid)
                    .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
            if (g.getEventCreatePolicy() == com.gatherit.api.group.entity.GroupMemberPolicy.OWNER_ONLY
                    && !g.isOwner(userId)) {
                throw new CustomException(ErrorCode.GROUP_EVENT_CREATE_FORBIDDEN);
            }
        }

        // pollId 는 클라이언트가 임의로 보낼 수 있다. 다른 그룹의 확정 투표를
        // 내 그룹 일정에 연결해 선점하지 못하도록 저장 전에 출처와 멤버십을 검증한다.
        Poll pollToLink = null;
        if (req.pollId() != null) {
            pollToLink = pollRepository.findById(req.pollId())
                    .orElseThrow(() -> new CustomException(ErrorCode.POLL_NOT_FOUND));
            if (!req.groupIds().contains(pollToLink.getGroupId())
                    || !groupMemberRepository.existsByGroupIdAndUserId(pollToLink.getGroupId(), userId)) {
                throw new CustomException(ErrorCode.POLL_FORBIDDEN);
            }
            if (!pollToLink.isConfirmed()) {
                throw new CustomException(ErrorCode.POLL_NOT_CONFIRMED);
            }
        }

        // 그림자 생성 — 원본 존재 + 본인이 원본을 볼 권한이 있어야 함 + 중첩 그림자 차단
        Event original = null;
        if (req.shadowOfEventId() != null) {
            original = eventRepository.findById(req.shadowOfEventId())
                    .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
            if (original.isShadow()) {
                throw new CustomException(ErrorCode.EVENT_SHADOW_NESTED);
            }
            List<Long> originalGroupIds = eventGroupRepository.findAllByEventId(original.getId()).stream()
                    .map(EventGroup::getGroupId).toList();
            assertCanView(userId, original.getId(), originalGroupIds);
            // 회차별 수정 override 는 부모 시리즈 삭제 시 함께 사라지므로
            // 독립 그림자를 연결하면 고아 일정이 남는다.
            if (original.getRecurrenceParentId() != null) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "수정된 회차는 다른 그룹에 알릴 수 없어요");
            }
            // 수정된 단일 회차 override 는 이 새 그림자의 그룹에 자동 복제되지 않는다.
            // 누락된 회차를 조용히 만들지 않도록 지원 전까지 명시적으로 차단한다.
            if (!eventRepository.findByRecurrenceParentId(original.getId()).isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "회차별 수정이 있는 반복 일정은 다른 그룹에 알릴 수 없어요");
            }
        }

        // 그림자는 원본의 allDay/reminder 따라감 (원본이 정체성).
        //  - reconcileEvent 가 shadow skip 하므로 reminder 는 실 효과 없지만 데이터 일관성 위해 null.
        //  - allDay 는 그림자 표시(today-detail, event-share, event/[id]) 가 ev.allDay 보고 분기하므로 필수.
        boolean finalAllDay = original != null ? original.isAllDay() : Boolean.TRUE.equals(req.allDay());
        Integer finalReminderMinutes = original != null ? null : req.reminderMinutesBefore();
        Integer finalReminderDays = original != null ? null : req.reminderAllDayDays();

        Event event = Event.builder()
                .title(req.title())
                .startAt(original != null ? original.getStartAt() : req.startAt())
                .endAt(original != null ? original.getEndAt() : req.endAt())
                .location(req.location())
                .memo(req.memo())
                .colorKey(req.colorKey())
                .createdBy(userId)
                .shadowOfEventId(req.shadowOfEventId())
                .fromPollId(req.pollId())
                .rrule(original != null ? original.getRrule() : req.rrule())
                .latitude(req.latitude())
                .longitude(req.longitude())
                .placeName(req.placeName())
                .roadAddress(req.roadAddress())
                .allDay(finalAllDay)
                .reminderMinutesBefore(finalReminderMinutes)
                .reminderAllDayDays(finalReminderDays)
                .build();
        if (original != null) event.syncTimingFrom(original); // EXDATE 포함; 클라이언트 값은 신뢰하지 않음
        eventRepository.save(event);

        // EventGroup 다수 삽입
        for (Long gid : req.groupIds()) {
            eventGroupRepository.save(EventGroup.builder()
                    .eventId(event.getId())
                    .groupId(gid)
                    .build());
        }

        // 폴 link — pollId 가 있으면 첫 사용자만 성공, 두 번째는 P009 로 차단.
        if (pollToLink != null) {
            if (!pollToLink.linkEventOnce(event.getId())) {
                throw new CustomException(ErrorCode.POLL_EVENT_ALREADY_LINKED);
            }
            try {
                pollRepository.saveAndFlush(pollToLink);  // @Version 낙관적 락 — 동시성 차단
            } catch (OptimisticLockingFailureException e) {
                throw new CustomException(ErrorCode.POLL_EVENT_ALREADY_LINKED);
            }
        }

        // 생성 직후 응답엔 본인 그룹 색 반영
        GroupColor color = computeUserEventColorSingle(userId, req.groupIds());

        // EVENT_CREATED 알림 — 그룹원 (생성자 제외) 에게 push. notify=false(AI 일괄)면 생략.
        // 그림자 일정은 원본을 본 적 없는 사람에게도 알림이 가야 하니 별도 처리 X — groupIds 자체로 공유됨.
        if (notify && req.shadowOfEventId() == null) {
            String when = EVENT_TIME_FMT.format(event.getStartAt());
            String title = "%s 일정이 추가됐어요".formatted(event.getTitle());
            String subtitle = when + (event.getLocation() != null ? " - " + event.getLocation() : "");
            String refKey = "event:" + event.getId();
            notificationService.pushScopedToUsers(recipientsOfGroups(req.groupIds(), userId),
                    NotificationType.EVENT_CREATED, title, subtitle, refKey, req.groupIds());
        }

        return enrichCreator(EventResponse.ofList(event, req.groupIds(), color), event.getCreatedBy());
    }

    /**
     * 위치 좌표/POI 메타 갱신 헬퍼.
     * - req.clearGeo() == true → 명시적 해제
     * - 그 외엔 null 아닌 값만 적용 (updateGeo 가 알아서 처리)
     */
    private void applyGeoUpdate(Event event, EventUpdateRequest req) {
        if (Boolean.TRUE.equals(req.clearGeo())) {
            event.clearGeo();
            return;
        }
        event.updateGeo(req.latitude(), req.longitude(), req.placeName(), req.roadAddress());
    }

    /** 주어진 그룹들의 멤버 union — 본인(트리거자) 제외. */
    private Set<Long> recipientsOfGroups(List<Long> groupIds, Long excludeUserId) {
        Set<Long> recipients = new HashSet<>();
        for (Long gid : groupIds) {
            for (GroupMember gm : groupMemberRepository.findAllByGroupId(gid)) {
                if (!gm.getUserId().equals(excludeUserId)) recipients.add(gm.getUserId());
            }
        }
        return recipients;
    }

    /** 단일 이벤트용 색 계산 — getDetail/create 응답에서 사용. */
    private GroupColor computeUserEventColorSingle(Long userId, List<Long> eventGroupIds) {
        Map<Long, GroupColor> myColorByGroup = new HashMap<>();
        for (GroupMember gm : groupMemberRepository.findAllByUserId(userId)) {
            myColorByGroup.put(gm.getGroupId(), gm.getColorKey());
        }
        Map<Long, Group> groupCache = new HashMap<>();
        groupRepository.findAllById(myColorByGroup.keySet()).forEach(g -> groupCache.put(g.getId(), g));
        return computeEventColor(eventGroupIds, myColorByGroup, groupCache);
    }

    /**
     * 캘린더 월간 조회 — N+1 방지를 위해 events 조회 후 EventGroup 을 단일 IN-query 로 일괄.
     * 구앱 요청은 작성자가 원본도 볼 때 그림자를 제외한다. 새 앱 opt-in 요청은 그룹별
     * 화면에서 그림자를 유지할 수 있도록 둘 다 반환한다.
     * 색 우선순위: 일반 그룹 (먼저 생긴 것 우선) > 내 캘린더(personal). per-user 색 사용.
     */
    @Transactional(readOnly = true)
    public List<EventResponse> findInRange(Long userId, Instant from, Instant to) {
        return findInRange(userId, from, to, false);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> findInRange(Long userId, Instant from, Instant to,
                                           boolean includeRecurringSources) {
        return toResponses(userId, includeRecurringSources
                ? eventRepository.findVisibleEventsInRangeIncludingRecurringSources(userId, from, to)
                : eventRepository.findVisibleEventsInRange(userId, from, to),
                !includeRecurringSources);
    }

    /** 일정 검색 — 제목/장소/메모/장소명 키워드(대소문자 무시), 가시성 적용. 최근(startAt desc)순. */
    @Transactional(readOnly = true)
    public List<EventResponse> search(Long userId, String q) {
        if (q == null || q.isBlank()) return List.of();
        return toResponses(userId, eventRepository.searchVisible(userId, q.trim()));
    }

    /** events → EventResponse 변환 (그룹맵핑/색/생성자 닉/색/그림자 dedup). findInRange/search 공유. */
    private List<EventResponse> toResponses(Long userId, List<Event> events) {
        return toResponses(userId, events, true);
    }

    private List<EventResponse> toResponses(Long userId, List<Event> events,
                                            boolean dedupCreatorShadows) {
        if (events.isEmpty()) return List.of();

        List<Long> eventIds = events.stream().map(Event::getId).toList();
        List<EventGroup> egs = eventGroupRepository.findAllByEventIdIn(eventIds);

        // event_id → group_ids 맵핑
        Map<Long, List<Long>> groupsByEvent = new HashMap<>();
        for (EventGroup eg : egs) {
            groupsByEvent.computeIfAbsent(eg.getEventId(), k -> new java.util.ArrayList<>()).add(eg.getGroupId());
        }

        // 본인의 그룹 색 + Group 정보 한 번에 — 색 priority 계산 용
        Map<Long, GroupColor> myColorByGroup = new HashMap<>();
        for (GroupMember gm : groupMemberRepository.findAllByUserId(userId)) {
            myColorByGroup.put(gm.getGroupId(), gm.getColorKey());
        }
        Map<Long, Group> groupCache = new HashMap<>();
        groupRepository.findAllById(myColorByGroup.keySet()).forEach(g -> groupCache.put(g.getId(), g));

        // 생성자 닉네임 + 본인 색 배치 조회 — createdBy 셋을 한 번에 (N+1 방지)
        Set<Long> creatorIds = new HashSet<>();
        for (Event e : events) if (e.getCreatedBy() != null) creatorIds.add(e.getCreatedBy());
        Map<Long, String> nickByUser = new HashMap<>();
        Map<Long, String> colorByUser = new HashMap<>();
        userRepository.findAllById(creatorIds).forEach(u -> {
            nickByUser.put(u.getId(), u.getNickname());
            if (u.getPersonalColor() != null) colorByUser.put(u.getId(), u.getPersonalColor());
        });

        // 본인이 만든 그림자 dedup — 원본이 같이 visible 인 경우만 그림자 제외
        java.util.Set<Long> visibleIds = new java.util.HashSet<>(eventIds);
        return events.stream()
                .filter(e -> {
                    if (!dedupCreatorShadows) return true;
                    if (e.getShadowOfEventId() == null) return true;
                    if (!e.getCreatedBy().equals(userId)) return true;
                    return !visibleIds.contains(e.getShadowOfEventId());
                })
                .map(e -> {
                    List<Long> gids = groupsByEvent.getOrDefault(e.getId(), List.of());
                    GroupColor color = computeEventColor(gids, myColorByGroup, groupCache);
                    return EventResponse.ofList(e, gids, color)
                            .withCreator(nickByUser.get(e.getCreatedBy()), colorByUser.get(e.getCreatedBy()));
                })
                .toList();
    }

    /**
     * 일정 색 우선순위:
     * 1. 일반 그룹 중 본인이 멤버인 것 → created_at 오름차순(먼저 생긴 것) 첫 번째
     * 2. 그게 없으면 내 캘린더(personal)
     * 3. 둘 다 없으면 team (fallback — 보통 안 일어남)
     */
    private GroupColor computeEventColor(List<Long> eventGroupIds,
                                         Map<Long, GroupColor> myColorByGroup,
                                         Map<Long, Group> groupCache) {
        Group nonPersonalOldest = null;
        Group personalGroup = null;
        for (Long gid : eventGroupIds) {
            Group g = groupCache.get(gid);
            if (g == null) continue;
            if (g.isPersonal()) {
                personalGroup = g;
            } else {
                if (nonPersonalOldest == null
                        || g.getCreatedAt().isBefore(nonPersonalOldest.getCreatedAt())) {
                    nonPersonalOldest = g;
                }
            }
        }
        if (nonPersonalOldest != null) {
            GroupColor c = myColorByGroup.get(nonPersonalOldest.getId());
            if (c != null) return c;
        }
        if (personalGroup != null) {
            GroupColor c = myColorByGroup.get(personalGroup.getId());
            if (c != null) return c;
        }
        return GroupColor.team;
    }

    @Transactional(readOnly = true)
    public EventResponse getDetail(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        List<Long> groupIds = eventGroupRepository.findAllByEventId(eventId).stream()
                .map(EventGroup::getGroupId).toList();
        assertCanView(userId, eventId, groupIds);

        // 원본이면 본인이 만든 그림자 list 채워서 반환 — detail 화면의 "내가 알린 그룹" 섹션
        List<EventResponse.ShadowSummary> myShadows = List.of();
        List<Long> originalGroupIds = List.of();
        if (event.isShadow()) {
            // 그림자면 원본의 groupIds 채워서 반환 — 알릴 그룹 picker 에서 제외 용도
            originalGroupIds = eventGroupRepository.findAllByEventId(event.getShadowOfEventId()).stream()
                    .map(EventGroup::getGroupId).toList();
        } else {
            List<Event> shadows = eventRepository.findByShadowOfEventIdAndCreatedBy(eventId, userId);
            if (!shadows.isEmpty()) {
                List<Long> shadowIds = shadows.stream().map(Event::getId).toList();
                List<EventGroup> shadowGroups = eventGroupRepository.findAllByEventIdIn(shadowIds);
                Map<Long, List<Long>> sgByEvent = new HashMap<>();
                for (EventGroup eg : shadowGroups) {
                    sgByEvent.computeIfAbsent(eg.getEventId(), k -> new java.util.ArrayList<>()).add(eg.getGroupId());
                }
                myShadows = shadows.stream()
                        .map(s -> new EventResponse.ShadowSummary(
                                s.getId(),
                                s.getTitle(),
                                sgByEvent.getOrDefault(s.getId(), List.of())))
                        .toList();
            }
        }
        GroupColor color = computeUserEventColorSingle(userId, groupIds);
        return enrichCreator(EventResponse.of(event, groupIds, color, originalGroupIds, myShadows), event.getCreatedBy());
    }

    @Transactional
    public EventResponse update(Long userId, Long eventId, EventUpdateRequest req) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        if (!event.isOwner(userId)) {
            throw new CustomException(ErrorCode.EVENT_FORBIDDEN);
        }
        // 반복 회차별 수정 — THIS / THIS_AND_FUTURE 는 별도 경로. ALL/미지정은 아래 기존 로직.
        EditScope scope = req.editScope() == null ? EditScope.ALL : req.editScope();
        if (scope != EditScope.ALL) {
            return updateOccurrence(userId, event, req, scope);
        }
        if (req.startAt() != null && req.endAt() != null && req.endAt().isBefore(req.startAt())) {
            throw new CustomException(ErrorCode.EVENT_TIME_INVALID);
        }

        // 시간/장소 변경 감지 (그림자 X) — EVENT_UPDATED 알림용
        boolean timeOrPlaceChanged = !event.isShadow() && (
                (req.startAt() != null && !req.startAt().equals(event.getStartAt())) ||
                (req.endAt()   != null && !req.endAt().equals(event.getEndAt())) ||
                (req.location() != null && !req.location().equals(event.getLocation()))
        );

        // 그룹 변경 감지 — 수정 후 새로 추가된 그룹 멤버에겐 EVENT_CREATED ("새 일정 추가됐어요") 발송 대상.
        List<Long> previousGroupIds = eventGroupRepository.findAllByEventId(eventId).stream()
                .map(EventGroup::getGroupId).toList();

        if (event.isShadow()) {
            // 그림자 — title/groupIds 만 수정 가능. 시간/장소/메모/좌표는 무시 (원본 따라감).
            if (req.title() != null) {
                event.update(req.title(), null, null, null, null, req.colorKey());
            }
        } else if (event.isFromPoll()) {
            // 폴 출처 일정 — groupIds 잠금 (그림자로만 추가). 시간/메모/위치 등은 수정 가능.
            if (req.groupIds() != null) {
                throw new CustomException(ErrorCode.EVENT_GROUPS_LOCKED);
            }
            event.update(req.title(), req.startAt(), req.endAt(), req.location(), req.memo(), req.colorKey());
            applyGeoUpdate(event, req);
        } else {
            // 일반 일정 — 시간/메모/장소/좌표뿐 아니라 groupIds 도 직접 수정 가능 (체크박스 흐름).
            event.update(req.title(), req.startAt(), req.endAt(), req.location(), req.memo(), req.colorKey());
            applyGeoUpdate(event, req);
        }

        // rrule 변경 — 그림자/폴 출처 X, 일반 일정만. null=변경 안 함, ""=반복 해제, 그 외=새 RRULE
        if (req.rrule() != null && !event.isShadow() && !event.isFromPoll()) {
            // 반복으로 바뀌면 모든 유저의 디데이 핀 정리 — 반복 일정은 핀 금지 정책(D-N 기준이 모호)인데
            // 핀 이후에 반복이 켜지는 우회 경로를 막는다 (v1.4 B).
            if (!req.rrule().isEmpty()) {
                ddayPinRepository.deleteAllByEventId(eventId);
            }
            event.updateRrule(req.rrule().isEmpty() ? null : req.rrule());
        }

        // allDay 토글 — 그림자/폴 출처 X (원본/폴 결과를 따라감). null = 변경 안 함.
        if (req.allDay() != null && !event.isShadow() && !event.isFromPoll()) {
            event.updateAllDay(req.allDay());
        }

        // 알림 override — 그림자는 원본 따라감. 그 외는 갱신. sentinel = Integer.MIN_VALUE 면 null 로 reset.
        if (!event.isShadow()) {
            event.updateReminder(req.reminderMinutesBefore(), req.reminderAllDayDays());
        }

        // 원본 시간 변경 → 그림자에 전파. "그림자는 원본 따라감" 정책이었지만 실제 전파 코드가 없어
        // 그림자가 걸린 타 그룹 캘린더(및 그림자 디데이)에 옛 시간이 박제되던 버그 (6/11 발견).
        if (!event.isShadow() && (req.startAt() != null || req.endAt() != null
                || req.allDay() != null || req.rrule() != null)) {
            for (Event shadow : eventRepository.findByShadowOfEventId(eventId)) {
                shadow.syncTimingFrom(event);
            }
        }

        // groupIds 갱신 — shadow 또는 일반(non-poll) 일정에서 허용
        if (req.groupIds() != null && (event.isShadow() || !event.isFromPoll())) {
            if (req.groupIds().isEmpty()) {
                throw new CustomException(ErrorCode.EVENT_NO_GROUPS);
            }
            for (Long gid : req.groupIds()) {
                if (!groupMemberRepository.existsByGroupIdAndUserId(gid, userId)) {
                    throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
                }
            }
            eventGroupRepository.deleteAllByEventId(eventId);
            eventGroupRepository.flush();
            for (Long gid : req.groupIds()) {
                eventGroupRepository.save(EventGroup.builder().eventId(eventId).groupId(gid).build());
            }
        }

        List<Long> finalGroupIds = eventGroupRepository.findAllByEventId(eventId).stream()
                .map(EventGroup::getGroupId).toList();
        GroupColor color = computeUserEventColorSingle(userId, finalGroupIds);

        // 알림 발송 — 두 케이스 구분:
        //  (a) 새로 추가된 그룹 멤버 → EVENT_CREATED ("새 일정 추가됐어요"). 그 그룹에 처음 보이는 일정이므로.
        //  (b) 기존 그룹에 그대로 남은 멤버 → EVENT_UPDATED (시간/장소 변경 시만).
        //  중복 대상은 (a) 우선 — 새 그룹 소속 멤버는 "변경" 보다 "추가" 가 정확한 표현.
        String refKey = "event:" + event.getId();
        String when = EVENT_TIME_FMT.format(event.getStartAt());

        List<Long> addedGroupIds = finalGroupIds.stream()
                .filter(g -> !previousGroupIds.contains(g)).toList();
        Set<Long> newGroupRecipients = addedGroupIds.isEmpty()
                ? java.util.Collections.emptySet()
                : recipientsOfGroups(addedGroupIds, userId);
        if (!newGroupRecipients.isEmpty() && !event.isShadow()) {
            String title = "%s 일정이 추가됐어요".formatted(event.getTitle());
            String subtitle = when + (event.getLocation() != null ? " - " + event.getLocation() : "");
            notificationService.pushScopedToUsers(newGroupRecipients,
                    NotificationType.EVENT_CREATED, title, subtitle, refKey, finalGroupIds);
        }

        if (timeOrPlaceChanged) {
            String title = "%s 일정이 변경됐어요".formatted(event.getTitle());
            String subtitle = when + (event.getLocation() != null ? " - " + event.getLocation() : "");
            // 기존 그룹 멤버만 — 새 그룹 멤버는 위에서 EVENT_CREATED 받음
            List<Long> existingGroupIds = finalGroupIds.stream()
                    .filter(previousGroupIds::contains).toList();
            List<Long> updateTargets = recipientsOfGroups(existingGroupIds, userId).stream()
                    .filter(memberId -> !newGroupRecipients.contains(memberId)) // 중복 방지 (한 사람이 여러 그룹 소속)
                    .toList();
            notificationService.pushScopedToUsers(updateTargets,
                    NotificationType.EVENT_UPDATED, title, subtitle, refKey, finalGroupIds);
        }

        return enrichCreator(EventResponse.ofList(event, finalGroupIds, color), event.getCreatedBy());
    }

    /** 기존 호출부 호환 — 시리즈 전체 삭제(ALL). */
    @Transactional
    public void delete(Long userId, Long eventId) {
        delete(userId, eventId, EditScope.ALL, null);
    }

    @Transactional
    public void delete(Long userId, Long eventId, EditScope scope, Instant occurrenceStart) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        if (!event.isOwner(userId)) {
            throw new CustomException(ErrorCode.EVENT_FORBIDDEN);
        }
        // 반복 회차별 삭제 — THIS / THIS_AND_FUTURE 는 별도 경로. ALL/미지정은 아래 기존 로직.
        if (scope != null && scope != EditScope.ALL) {
            deleteOccurrence(userId, event, scope, occurrenceStart);
            return;
        }
        // 원본 삭제 시 이 일정 기반 모든 그림자(누가 만들었든) cascade — 타 그룹 고아 유령 일정 방지
        // (그림자를 지우는 경우 findByShadowOfEventId 는 빈 결과라 무해)
        for (Event shadow : eventRepository.findByShadowOfEventId(eventId)) {
            eventGroupRepository.deleteAllByEventId(shadow.getId());
            ddayPinRepository.deleteAllByEventId(shadow.getId());
            eventRepository.delete(shadow);
        }
        // 시리즈 삭제 시 이 시리즈의 오버라이드(단일 수정 회차)도 cascade — 고아 방지
        for (Event ov : eventRepository.findByRecurrenceParentId(eventId)) {
            eventGroupRepository.deleteAllByEventId(ov.getId());
            ddayPinRepository.deleteAllByEventId(ov.getId());
            eventRepository.delete(ov);
        }
        eventGroupRepository.deleteAllByEventId(eventId);
        ddayPinRepository.deleteAllByEventId(eventId);   // 디데이 핀 죽은 참조 정리 (모든 유저)
        eventRepository.delete(event);
        // 폴 연결 ID는 의도적으로 유지한다. Poll 응답은 실제 Event 존재 여부를 확인해
        // "연결된 일정 삭제됨"을 표시하고, linkEventOnce 는 재등록을 차단한다.
        // 죽은 참조 알림 정리 — 일정이 전역 삭제됐으므로 모든 수신자의 event:{id} 알림 제거(무한로딩/유령탭 차단).
        // v1.3.1 은 그룹탈퇴/계정탈퇴 경로만 정리했고, 작성자 직접 삭제(가장 흔한 경로)는 빠져 있었음(엣지 감사 P2-1).
        notificationService.deleteByRefKeys(java.util.List.of("event:" + eventId));
    }

    /**
     * 내 캘린더(개인 그룹) 초기화 — 개인 그룹의 모든 일정 제거.
     *  - 개인 그룹에만 있는 일정 → 완전 삭제
     *  - 다른 그룹에도 공유된 일정 → 개인 연결만 해제 (다른 그룹엔 그대로 유지)
     * 개인 그룹은 본인 1인 멤버라 여기 일정은 전부 본인 것. 다른 그룹은 건드리지 않음.
     */
    @Transactional
    public int resetPersonalCalendar(Long userId) {
        Group personal = groupRepository.findAllByMemberUserId(userId).stream()
                .filter(Group::isPersonal)
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        Long pid = personal.getId();
        int removed = 0;
        for (EventGroup link : eventGroupRepository.findAllByGroupId(pid)) {
            Long eventId = link.getEventId();
            if (eventGroupRepository.findAllByEventId(eventId).size() <= 1) {
                // 개인 전용 원본 삭제 시 그 원본의 그림자(타 그룹)도 cascade — 고아 유령 일정 방지
                for (Event shadow : eventRepository.findByShadowOfEventId(eventId)) {
                    eventGroupRepository.deleteAllByEventId(shadow.getId());
                    ddayPinRepository.deleteAllByEventId(shadow.getId());
                    eventRepository.deleteById(shadow.getId());
                }
                eventGroupRepository.deleteAllByEventId(eventId);
                ddayPinRepository.deleteAllByEventId(eventId);
                eventRepository.deleteById(eventId);
            } else {
                eventGroupRepository.deleteByEventIdAndGroupId(eventId, pid);
            }
            removed++;
        }
        return removed;
    }

    // ─── 디데이 핀 (v1.4 B) — 개인 설정. 노출 선정/D-N 계산은 앱 담당 ─────────────

    @Transactional
    public void pinDday(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        List<Long> groupIds = eventGroupRepository.findAllByEventId(eventId).stream()
                .map(EventGroup::getGroupId).toList();
        assertCanView(userId, eventId, groupIds);
        // 반복 일정은 "다음 발생" 계산이 필요해 v1 에선 핀 금지 (위젯/헤더 D-N 의미가 모호).
        if (event.getRrule() != null && !event.getRrule().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반복 일정은 디데이로 설정할 수 없어요");
        }
        if (ddayPinRepository.findByUserIdAndEventId(userId, eventId).isEmpty()) {
            ddayPinRepository.save(new DdayPin(userId, eventId));   // 멱등 — 이미 있으면 no-op
        }
    }

    /**
     * 대표 디데이 설정/해제 — 접힌 카드/위젯에 우선 노출할 1개. 유저당 1개(설정 시 기존 대표 자동 해제).
     * 핀이 없으면 핀부터 생성(가시성/반복 검증 포함). 해제하면 "자동(가장 가까운 미래)" 모드로 복귀.
     */
    @Transactional
    public void setPrimaryDday(Long userId, Long eventId, boolean primary) {
        if (primary) {
            pinDday(userId, eventId);   // 없으면 생성 + 검증 (멱등)
            for (DdayPin existing : ddayPinRepository.findAllByUserIdAndPrimaryTrue(userId)) {
                existing.setPrimary(false);
            }
            ddayPinRepository.findByUserIdAndEventId(userId, eventId)
                    .ifPresent(pin -> pin.setPrimary(true));
        } else {
            ddayPinRepository.findByUserIdAndEventId(userId, eventId)
                    .ifPresent(pin -> pin.setPrimary(false));
        }
    }

    @Transactional
    public void unpinDday(Long userId, Long eventId) {
        ddayPinRepository.findByUserIdAndEventId(userId, eventId)
                .ifPresent(ddayPinRepository::delete);              // 멱등
    }

    /**
     * 내 디데이 핀 목록 — 일정 데이터와 함께. 과거 일정 포함(필터는 앱 — 상세 토글 상태 표시용).
     * 일정이 삭제된 핀은 여기서 lazy 정리, 그룹 탈퇴로 안 보이는 핀은 보존(가시성 회복 시 부활).
     */
    @Transactional
    public List<DdayPinResponse> listDdayPins(Long userId) {
        List<DdayPin> pins = ddayPinRepository.findAllByUserId(userId);
        if (pins.isEmpty()) return List.of();
        List<Long> ids = pins.stream().map(DdayPin::getEventId).toList();

        Map<Long, Event> visible = new HashMap<>();
        for (Event e : eventRepository.findVisibleByIdIn(userId, ids)) visible.put(e.getId(), e);

        // 일정 자체가 사라진 핀(전역 하드 삭제 경로 외 잔여) lazy 정리 — 보이는지와 무관하게 존재 여부로 판단
        Set<Long> existing = new java.util.HashSet<>();
        for (Event e : eventRepository.findAllById(ids)) existing.add(e.getId());
        List<Long> dead = ids.stream().filter(id -> !existing.contains(id)).toList();
        if (!dead.isEmpty()) ddayPinRepository.deleteAllByEventIdIn(dead);

        Map<Long, DdayPin> pinByEvent = new HashMap<>();
        for (DdayPin pin : pins) pinByEvent.put(pin.getEventId(), pin);
        return ids.stream()
                .map(visible::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Event::getStartAt))
                .map(e -> new DdayPinResponse(e.getId(), e.getTitle(), e.getStartAt(), e.getEndAt(), e.isAllDay(),
                        pinByEvent.get(e.getId()) != null && pinByEvent.get(e.getId()).isPrimary()))
                .toList();
    }

    public record DdayPinResponse(Long eventId, String title, Instant startAt, Instant endAt, boolean allDay, boolean primary) {}

    // ─── 반복 회차별 수정/삭제 (v1.5.2 EXDATE) ───────────────────────────────

    /** scope != ALL 인 수정 처리 — 반복 owner 일반 일정에서만. */
    private EventResponse updateOccurrence(Long userId, Event series, EventUpdateRequest req, EditScope scope) {
        assertRecurringOwnable(series);
        if (req.occurrenceStart() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "회차 정보가 필요해요");
        }
        if (req.startAt() != null && req.endAt() != null && req.endAt().isBefore(req.startAt())) {
            throw new CustomException(ErrorCode.EVENT_TIME_INVALID);
        }
        Instant occ = req.occurrenceStart();
        // 첫 회차부터 이후 수정은 시리즈 전체 변경과 동등하므로 ALL 경로로 처리한다.
        if (scope == EditScope.THIS_AND_FUTURE && !occ.isAfter(series.getStartAt())) {
            return update(userId, series.getId(), withAllScope(req));
        }
        // 수정 override/새 시리즈를 그림자 그룹에 복제하는 계약은 아직 없다.
        // API 직접 호출로 타 그룹 회차가 조용히 사라지는 일을 막는다.
        if (!eventRepository.findByShadowOfEventId(series.getId()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "다른 그룹에 알린 반복 일정은 회차별 수정을 지원하지 않아요");
        }
        List<Long> groupIds = eventGroupRepository.findAllByEventId(series.getId()).stream()
                .map(EventGroup::getGroupId).toList();

        if (scope == EditScope.THIS) {
            // 원본 회차 제외 + 오버라이드 1건 생성(같은 회차 오버라이드 있으면 교체)
            series.addExdate(occ);
            for (Event old : eventRepository.findByRecurrenceParentIdAndRecurrenceId(series.getId(), occ)) {
                eventGroupRepository.deleteAllByEventId(old.getId());
                eventRepository.delete(old);
            }
            Event override = buildEditedFrom(series, userId, req, occ, null, series.getId(), occ);
            eventRepository.save(override);
            copyGroups(groupIds, override.getId());
            return responseFor(override, userId);
        }

        String originalRrule = series.getRrule();
        series.updateRrule(RruleEdit.withUntil(originalRrule, occ.minusSeconds(1)));
        series.pruneExdatesFrom(occ);
        // occ 부터 새 시리즈 — 새 rrule 은 클라 우선(count 재계산 반영), 없으면 원본 규칙
        String newRrule = (req.rrule() != null && !req.rrule().isEmpty()) ? req.rrule() : originalRrule;
        Event newSeries = buildEditedFrom(series, userId, req, occ, newRrule, null, null);
        eventRepository.save(newSeries);
        copyGroups(groupIds, newSeries.getId());
        return responseFor(newSeries, userId);
    }

    /** scope != ALL 인 삭제 처리. */
    private void deleteOccurrence(Long userId, Event series, EditScope scope, Instant occ) {
        assertRecurringOwnable(series);
        if (occ == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "회차 정보가 필요해요");
        }
        if (scope == EditScope.THIS) {
            series.addExdate(occ);
            syncTimingToShadows(series);
            deleteOverrides(series.getId(), o -> o.getRecurrenceId() != null && o.getRecurrenceId().equals(occ));
            return;
        }
        // THIS_AND_FUTURE — 첫 회차 이하이면 시리즈 통째 삭제
        if (!occ.isAfter(series.getStartAt())) {
            delete(userId, series.getId(), EditScope.ALL, null);
            return;
        }
        series.updateRrule(RruleEdit.withUntil(series.getRrule(), occ.minusSeconds(1)));
        series.pruneExdatesFrom(occ);
        syncTimingToShadows(series);
        deleteOverrides(series.getId(), o -> o.getRecurrenceId() != null && !o.getRecurrenceId().isBefore(occ));
    }

    /** 회차별 조작 전제 — 반복 시리즈 + 그림자/폴 출처 아님. (owner 는 호출 전 검증됨) */
    private void assertRecurringOwnable(Event event) {
        if (event.getRrule() == null || event.getRrule().isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반복 일정이 아니에요");
        }
        if (event.isShadow() || event.isFromPoll()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이 일정은 회차별 수정을 지원하지 않아요");
        }
    }

    /** 오버라이드/새 시리즈 Event 를 series 기준 + req 수정값으로 조립. */
    private Event buildEditedFrom(Event series, Long userId, EventUpdateRequest req, Instant occ,
                                  String rrule, Long recurrenceParentId, Instant recurrenceId) {
        Duration dur = Duration.between(series.getStartAt(), series.getEndAt());
        Instant newStart = req.startAt() != null ? req.startAt() : occ;
        Instant newEnd = req.endAt() != null ? req.endAt() : newStart.plus(dur);
        boolean clearGeo = Boolean.TRUE.equals(req.clearGeo());
        Integer rm = req.reminderMinutesBefore();
        Integer newRm = rm == null ? series.getReminderMinutesBefore() : (rm == Integer.MIN_VALUE ? null : rm);
        Integer rd = req.reminderAllDayDays();
        Integer newRd = rd == null ? series.getReminderAllDayDays() : (rd == Integer.MIN_VALUE ? null : rd);
        return Event.builder()
                .title(req.title() != null ? req.title() : series.getTitle())
                .startAt(newStart)
                .endAt(newEnd)
                .location(req.location() != null ? req.location() : series.getLocation())
                .memo(req.memo() != null ? req.memo() : series.getMemo())
                .colorKey(req.colorKey() != null ? req.colorKey() : series.getColorKey())
                .createdBy(userId)
                .rrule(rrule == null || rrule.isBlank() ? null : rrule)
                .latitude(clearGeo ? null : (req.latitude() != null ? req.latitude() : series.getLatitude()))
                .longitude(clearGeo ? null : (req.longitude() != null ? req.longitude() : series.getLongitude()))
                .placeName(clearGeo ? null
                        : (req.placeName() != null ? (req.placeName().isEmpty() ? null : req.placeName()) : series.getPlaceName()))
                .roadAddress(clearGeo ? null
                        : (req.roadAddress() != null ? (req.roadAddress().isEmpty() ? null : req.roadAddress()) : series.getRoadAddress()))
                .allDay(req.allDay() != null ? req.allDay() : series.isAllDay())
                .reminderMinutesBefore(newRm)
                .reminderAllDayDays(newRd)
                .recurrenceParentId(recurrenceParentId)
                .recurrenceId(recurrenceId)
                .build();
    }

    private void copyGroups(List<Long> groupIds, Long targetEventId) {
        for (Long gid : groupIds) {
            eventGroupRepository.save(EventGroup.builder().eventId(targetEventId).groupId(gid).build());
        }
    }

    /** 회차 삭제 결과를 그림자에도 전파한다. 기존 RRULE 누락 그림자도 복구된다. */
    private void syncTimingToShadows(Event series) {
        for (Event shadow : eventRepository.findByShadowOfEventId(series.getId())) {
            shadow.syncTimingFrom(series);
        }
    }

    private void deleteOverrides(Long seriesId, Predicate<Event> filter) {
        for (Event ov : eventRepository.findByRecurrenceParentId(seriesId)) {
            if (filter.test(ov)) {
                eventGroupRepository.deleteAllByEventId(ov.getId());
                ddayPinRepository.deleteAllByEventId(ov.getId());
                eventRepository.delete(ov);
            }
        }
    }

    private EventUpdateRequest withAllScope(EventUpdateRequest r) {
        return new EventUpdateRequest(r.title(), r.startAt(), r.endAt(), r.location(), r.memo(),
                r.colorKey(), r.groupIds(), r.rrule(), r.latitude(), r.longitude(), r.placeName(),
                r.roadAddress(), r.clearGeo(), r.allDay(), r.reminderMinutesBefore(), r.reminderAllDayDays(),
                EditScope.ALL, null);
    }

    private EventResponse responseFor(Event e, Long userId) {
        List<Long> gids = eventGroupRepository.findAllByEventId(e.getId()).stream()
                .map(EventGroup::getGroupId).toList();
        GroupColor color = computeUserEventColorSingle(userId, gids);
        return enrichCreator(EventResponse.ofList(e, gids, color), e.getCreatedBy());
    }

    /** 권한 체크 — 일정 공유된 그룹 중 하나라도 사용자 멤버이면 OK */
    private void assertCanView(Long userId, Long eventId, List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }
        for (Long gid : groupIds) {
            if (groupMemberRepository.existsByGroupIdAndUserId(gid, userId)) return;
        }
        throw new CustomException(ErrorCode.EVENT_FORBIDDEN);
    }
}

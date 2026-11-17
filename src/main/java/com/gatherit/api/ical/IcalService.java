package com.gatherit.api.ical;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * iCalendar(.ics) export.
 *
 * 외부 의존성(ical4j) 없이 VCALENDAR 텍스트를 직접 생성 — 시각은 UTC `Z` 로 내보내 VTIMEZONE
 * 불필요(클라이언트가 로컬 변환), 종일은 `VALUE=DATE`. RFC 5545 TEXT escape + 75-octet folding.
 * (외부 .ics import 를 붙일 때는 그때 ical4j 도입 — 파싱이 어려운 쪽.)
 *
 * 범위 2종:
 *  - 전체: 내가 속한 모든 그룹 합본 (MY 화면 "모든 데이터 내보내기")
 *  - 그룹별: 단일 그룹 (옵션 — 그룹별 색 분리)
 * 인증 2종: JWT(userId) 다운로드 / icalToken 구독(webcal, SecurityConfig public).
 */
@Service
@RequiredArgsConstructor
public class IcalService {

    private final EventRepository eventRepository;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter UTC_DT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter KST_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(KST);

    // ---------- 구독 토큰 ----------

    /** 구독 토큰 보장 — 없으면 생성해서 반환 (idempotent). */
    @Transactional
    public String ensureToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.getIcalToken() == null || user.getIcalToken().isBlank()) {
            user.updateIcalToken(generateToken());
        }
        return user.getIcalToken();
    }

    /** 구독 토큰 재발급 — 기존 구독 URL 즉시 무효화. */
    @Transactional
    public String rotateToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.updateIcalToken(generateToken());
        return user.getIcalToken();
    }

    private static String generateToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf); // ~43 chars
    }

    // ---------- 전체/선택 (내 그룹들 합본) ----------

    /** 다운로드용 — 선택 그룹(없으면 내 전체 그룹)의 일정. */
    @Transactional(readOnly = true)
    public String buildUserCalendar(Long userId, List<Long> requestedGroups) {
        return render(calName(requestedGroups), eventsFor(userId, requestedGroups));
    }

    /** 구독용 — 토큰으로 사용자 식별 후 선택 그룹의 일정. 실패 시 empty → 404. */
    @Transactional(readOnly = true)
    public Optional<String> buildUserCalendarByToken(String token, List<Long> requestedGroups) {
        if (token == null || token.isBlank()) return Optional.empty();
        return userRepository.findByIcalToken(token)
                .map(u -> render(calName(requestedGroups), eventsFor(u.getId(), requestedGroups)));
    }

    /** 선택 그룹 ∩ 내 멤버 그룹의 일정. 선택이 비면 내 전체 그룹. (멤버 아닌 그룹 요청은 무시 — 보안) */
    private List<Event> eventsFor(Long userId, List<Long> requestedGroups) {
        List<Long> myGroups = groupMemberRepository.findAllByUserId(userId).stream()
                .map(GroupMember::getGroupId).toList();
        List<Long> target = (requestedGroups == null || requestedGroups.isEmpty())
                ? myGroups
                : requestedGroups.stream().filter(myGroups::contains).toList();
        return target.isEmpty() ? List.of() : eventRepository.findAllByGroupIds(target);
    }

    private static String calName(List<Long> requestedGroups) {
        return (requestedGroups == null || requestedGroups.isEmpty())
                ? "GatherIt - 전체 일정"
                : "GatherIt - 선택 일정";
    }

    // ---------- 그룹별 (옵션) ----------

    /** 다운로드용 — 호출자가 그 그룹의 멤버일 때만. 아니면 empty → 404. */
    @Transactional(readOnly = true)
    public Optional<String> buildGroupCalendar(Long groupId, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            return Optional.empty();
        }
        return groupRepository.findById(groupId)
                .map(g -> render("GatherIt - " + g.getName(), eventRepository.findAllByGroupId(groupId)));
    }

    /** 구독용 — 토큰으로 사용자 식별 후 그 그룹 멤버일 때만. 실패는 전부 empty → 404 (정보 노출 X). */
    @Transactional(readOnly = true)
    public Optional<String> buildGroupCalendarByToken(String token, Long groupId) {
        if (token == null || token.isBlank()) return Optional.empty();
        return userRepository.findByIcalToken(token)
                .filter(u -> groupMemberRepository.existsByGroupIdAndUserId(groupId, u.getId()))
                .flatMap(u -> groupRepository.findById(groupId))
                .map(g -> render("GatherIt - " + g.getName(), eventRepository.findAllByGroupId(groupId)));
    }

    // ---------- VCALENDAR 렌더 ----------

    private String render(String calendarName, List<Event> events) {
        // 생성자 닉네임 배치 조회 (N+1 방지) — DESCRIPTION 표기용
        Map<Long, String> nickById = userRepository.findAllById(
                events.stream().map(Event::getCreatedBy).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, User::getNickname));

        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//gatherit//calendar//KO");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + esc(calendarName));
        line(sb, "X-WR-TIMEZONE:Asia/Seoul");

        String stamp = UTC_DT.format(Instant.now());
        for (Event e : events) {
            boolean isOverride = e.getRecurrenceParentId() != null;
            line(sb, "BEGIN:VEVENT");
            // 오버라이드(단일 수정 회차)는 시리즈 원본 UID + RECURRENCE-ID 로 묶어 외부 캘린더가 회차 대체로 인식.
            line(sb, "UID:event-" + (isOverride ? e.getRecurrenceParentId() : e.getId()) + "@gatherit.co.kr");
            line(sb, "DTSTAMP:" + stamp);
            if (e.isAllDay()) {
                // 종일: DATE 값, DTEND 는 exclusive(다음 날)
                line(sb, "DTSTART;VALUE=DATE:" + KST_DATE.format(e.getStartAt()));
                line(sb, "DTEND;VALUE=DATE:" + KST_DATE.format(e.getEndAt().plus(1, ChronoUnit.DAYS)));
            } else {
                line(sb, "DTSTART:" + UTC_DT.format(e.getStartAt()));
                line(sb, "DTEND:" + UTC_DT.format(e.getEndAt()));
            }
            if (isOverride) {
                if (e.isAllDay()) line(sb, "RECURRENCE-ID;VALUE=DATE:" + KST_DATE.format(e.getRecurrenceId()));
                else line(sb, "RECURRENCE-ID:" + UTC_DT.format(e.getRecurrenceId()));
            }
            if (e.getRrule() != null && !e.getRrule().isBlank()) {
                line(sb, "RRULE:" + e.getRrule()); // 이미 RFC5545 RRULE 포맷으로 저장됨
            }
            // 반복 예외(EXDATE) — 제외된 회차. 외부 캘린더가 해당 회차를 표시하지 않도록.
            java.util.List<java.time.Instant> exdates = e.getExdatesList();
            if (!exdates.isEmpty()) {
                if (e.isAllDay()) {
                    line(sb, "EXDATE;VALUE=DATE:" + exdates.stream().map(KST_DATE::format).collect(Collectors.joining(",")));
                } else {
                    line(sb, "EXDATE:" + exdates.stream().map(UTC_DT::format).collect(Collectors.joining(",")));
                }
            }
            line(sb, "SUMMARY:" + esc(e.getTitle()));
            String loc = firstNonBlank(e.getPlaceName(), e.getRoadAddress(), e.getLocation());
            if (loc != null) line(sb, "LOCATION:" + esc(loc));
            // 좌표 보존 — RFC5545 GEO:lat;lng. import(IcalImportService) 가 lat/lng 복원 → 내보내고 불러와도 지도 좌표 유지.
            if (e.getLatitude() != null && e.getLongitude() != null) {
                line(sb, "GEO:" + e.getLatitude() + ";" + e.getLongitude());
            }
            line(sb, "DESCRIPTION:" + esc(buildDescription(e, nickById.get(e.getCreatedBy()))));
            line(sb, "URL:gatherit://event/" + e.getId());
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    private String buildDescription(Event e, String creatorNickname) {
        StringBuilder d = new StringBuilder();
        if (e.getMemo() != null && !e.getMemo().isBlank()) {
            d.append(e.getMemo()).append("\n\n");
        }
        d.append("만든 사람: ").append(creatorNickname == null ? "?" : creatorNickname);
        d.append("\nGatherIt에서 열기: gatherit://event/").append(e.getId());
        return d.toString();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // ---------- RFC 5545 직렬화 헬퍼 ----------

    /** 한 줄을 75-octet fold 후 CRLF 로 append. */
    private static void line(StringBuilder sb, String raw) {
        sb.append(fold(raw)).append("\r\n");
    }

    /** TEXT 값 escape (§3.3.11): backslash / semicolon / comma / newline. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    /** 75-octet line folding (§3.1) — 멀티바이트(한글) 안 쪼개도록 UTF-8 바이트 기준, 안전하게 73. */
    private static String fold(String line) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int charCount = Character.charCount(cp);
            String piece = line.substring(i, i + charCount);
            int pieceBytes = piece.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + pieceBytes > 73) {
                out.append("\r\n "); // continuation line = CRLF + space
                bytes = 1;
            }
            out.append(piece);
            bytes += pieceBytes;
            i += charCount;
        }
        return out.toString();
    }
}

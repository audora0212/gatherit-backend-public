package com.gatherit.api.ical;

import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.event.repository.EventGroupRepository;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.repository.GroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 외부 .ics import — 업로드한 캘린더를 파싱해 "내 캘린더(개인)" 그룹에 일정으로 생성.
 *
 * 일회성 스냅샷(구글/애플과 라이브 sync 아님). 들어온 일정은 일반 일정이라 이후 개별 수정으로
 * 다른 그룹에 포함 가능(기존 event-edit 그룹 멀티선택). createdBy = 불러온 본인.
 *
 * ical4j 없이 직접 파싱:
 *  - line unfolding(접힘 복원) + TEXT unescape
 *  - 시간대: java.time ZoneId 로 IANA TZID 해석 — `;TZID=Asia/Seoul`, `Z`(UTC), `VALUE=DATE`(종일),
 *    floating(미표기)=KST. VTIMEZONE 블록 파싱 불필요(ZoneId 가 tz DB 보유).
 *  - 재import 중복 방지: 원본 UID(import_uid). 개수 cap.
 */
@Service
@RequiredArgsConstructor
public class IcalImportService {

    private final EventRepository eventRepository;
    private final EventGroupRepository eventGroupRepository;
    private final GroupRepository groupRepository;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_IMPORT = 300;
    private static final int USER_EVENT_CAP = 2000;   // 사용자당 총 일정 상한 — 반복 import 폭주 방지
    private static final int MAX_ICS_CHARS = 2_000_000;   // 외부 .ics 길이 상한(~2-4MB) — 메모리 폭주 방지

    public record ImportResult(int imported, int skipped, boolean truncated) {}

    @Transactional
    public ImportResult importIcs(Long userId, String ics) {
        if (ics == null || ics.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        if (ics.length() > MAX_ICS_CHARS) {
            throw new CustomException(ErrorCode.INVALID_INPUT);   // 너무 큰 .ics 거부
        }
        Group personal = groupRepository.findAllByMemberUserId(userId).stream()
                .filter(Group::isPersonal)
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));

        List<Raw> raws = parse(ics);
        // 사용자 총량 cap — 반복 import 로 일정 폭주 방지
        long existing = eventRepository.countByCreatedBy(userId);
        int capRemaining = (int) Math.max(0, USER_EVENT_CAP - existing);
        boolean truncated = raws.size() > MAX_IMPORT || raws.size() > capRemaining;
        int limit = Math.min(Math.min(raws.size(), MAX_IMPORT), capRemaining);

        int imported = 0, skipped = 0;
        for (int i = 0; i < limit; i++) {
            Raw r = raws.get(i);
            if (r.startAt == null) { skipped++; continue; }                    // DTSTART 없는 항목 skip
            // UID 없는 항목도 (start+summary+end) 합성 UID 로 재import 중복 방지
            String effUid = (r.uid != null && !r.uid.isBlank())
                    ? r.uid
                    : "syn-" + Integer.toHexString((r.startAt + "|" + orDefault(r.summary, "") + "|"
                        + (r.endAt != null ? r.endAt.toString() : "")).hashCode());
            if (eventRepository.existsByCreatedByAndImportUid(userId, effUid)) {
                skipped++;                                                      // 이미 import 한 일정
                continue;
            }
            Instant start = r.startAt;
            Instant end = r.endAt != null ? r.endAt
                    : start.plus(r.allDay ? Duration.ofHours(23).plusMinutes(59) : Duration.ofHours(1));
            if (end.isBefore(start)) end = start;

            Event event = Event.builder()
                    .title(trunc(orDefault(r.summary, "(제목 없음)"), 100))
                    .startAt(start)
                    .endAt(end)
                    .location(trunc(r.location, 200))
                    .memo(trunc(r.description, 1000))
                    .createdBy(userId)
                    .rrule(trunc(r.rrule, 200))
                    .allDay(r.allDay)
                    .latitude(r.lat)
                    .longitude(r.lng)
                    .importUid(trunc(effUid, 255))
                    .build();
            eventRepository.save(event);
            eventGroupRepository.save(EventGroup.builder()
                    .eventId(event.getId())
                    .groupId(personal.getId())
                    .build());
            imported++;
        }
        return new ImportResult(imported, skipped, truncated);
    }

    // ---------- 파싱 ----------

    private static final class Raw {
        String uid, summary, rrule, location, description;
        Instant startAt, endAt;
        boolean allDay;
        Double lat, lng;
    }

    private List<Raw> parse(String ics) {
        // unfold: CRLF/LF 뒤 (space|tab) 로 시작하는 continuation line 을 앞 줄에 이어붙임
        String unfolded = ics.replace("\r\n", "\n").replaceAll("\n[ \t]", "");
        List<Raw> out = new ArrayList<>();
        Raw cur = null;
        for (String line : unfolded.split("\n")) {
            String t = line.trim();
            if (t.equalsIgnoreCase("BEGIN:VEVENT")) { cur = new Raw(); continue; }
            if (t.equalsIgnoreCase("END:VEVENT")) { if (cur != null) out.add(cur); cur = null; continue; }
            if (cur == null) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String left = line.substring(0, colon);          // NAME;param=val;...
            String value = line.substring(colon + 1);
            int semi = left.indexOf(';');
            String name = (semi < 0 ? left : left.substring(0, semi)).trim().toUpperCase();
            String params = semi < 0 ? "" : left.substring(semi + 1);

            switch (name) {
                case "UID" -> cur.uid = value.trim();
                case "SUMMARY" -> cur.summary = unescape(value);
                case "LOCATION" -> cur.location = unescape(value);
                case "DESCRIPTION" -> cur.description = unescape(value);
                case "RRULE" -> cur.rrule = value.trim();
                case "GEO" -> {                                   // RFC5545 GEO:lat;lng → 좌표 보존
                    String[] g = value.trim().split(";");
                    if (g.length == 2) {
                        try { cur.lat = Double.parseDouble(g[0].trim()); cur.lng = Double.parseDouble(g[1].trim()); }
                        catch (NumberFormatException ignored) { /* 잘못된 GEO 무시 */ }
                    }
                }
                case "DTSTART" -> {
                    Pd p = parseDate(params, value.trim());
                    cur.startAt = p.instant;
                    cur.allDay = p.isDate;
                }
                case "DTEND" -> {
                    Pd p = parseDate(params, value.trim());
                    // 종일 DTEND 는 exclusive(다음 날) → 마지막 날 23:59:59 로 보정
                    cur.endAt = (p.isDate && p.date != null)
                            ? p.date.minusDays(1).atTime(23, 59, 59).atZone(KST).toInstant()
                            : p.instant;
                }
                default -> { /* ignore VALARM/RDATE/EXDATE/etc */ }
            }
        }
        return out;
    }

    private record Pd(Instant instant, boolean isDate, LocalDate date) {}

    private static Pd parseDate(String params, String value) {
        boolean isDate = params.toUpperCase().contains("VALUE=DATE")
                || (value.length() == 8 && !value.contains("T"));
        if (isDate) {
            LocalDate d = LocalDate.parse(value, D);
            return new Pd(d.atStartOfDay(KST).toInstant(), true, d);          // 종일 시작 = 그 날 KST 00:00
        }
        if (value.endsWith("Z")) {
            LocalDateTime ldt = LocalDateTime.parse(value.substring(0, value.length() - 1), DT);
            return new Pd(ldt.atZone(ZoneOffset.UTC).toInstant(), false, null);
        }
        String tzid = paramValue(params, "TZID");
        ZoneId zone = tzid != null ? safeZone(tzid) : KST;                    // floating = KST
        LocalDateTime ldt = LocalDateTime.parse(value, DT);
        return new Pd(ldt.atZone(zone).toInstant(), false, null);
    }

    private static ZoneId safeZone(String tzid) {
        try {
            return ZoneId.of(tzid);
        } catch (Exception e) {
            return KST;
        }
    }

    private static String paramValue(String params, String key) {
        for (String p : params.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return p.substring(eq + 1).trim().replace("\"", "");
            }
        }
        return null;
    }

    /** RFC 5545 TEXT unescape (§3.3.11): \\n \\, \\; \\\\ */
    private static String unescape(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n' || n == 'N') b.append('\n');
                else if (n == ',') b.append(',');
                else if (n == ';') b.append(';');
                else b.append(n); // '\\' 포함
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}

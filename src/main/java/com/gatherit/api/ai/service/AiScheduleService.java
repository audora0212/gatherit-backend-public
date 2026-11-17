package com.gatherit.api.ai.service;

import com.gatherit.api.ai.config.AiProperties;
import com.gatherit.api.ai.dto.CommitRequest;
import com.gatherit.api.ai.dto.CommitResponse;
import com.gatherit.api.ai.dto.EventCandidate;
import com.gatherit.api.ai.dto.ExtractRequest;
import com.gatherit.api.ai.dto.ExtractResponse;
import com.gatherit.api.ai.dto.QuotaResponse;
import com.gatherit.api.ai.entity.AiCommitReceipt;
import com.gatherit.api.ai.entity.AiImportLog;
import com.gatherit.api.ai.extractor.Extraction;
import com.gatherit.api.ai.extractor.LlmExtractor;
import com.gatherit.api.ai.repository.AiCommitReceiptRepository;
import com.gatherit.api.ai.repository.AiImportLogRepository;
import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.event.service.EventService;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI 일정 가져오기 오케스트레이션 — 쿼터 → 추출(LlmExtractor) → 서버 검증/정규화 → 중복감지 → 응답.
 * 저장(commit)은 기존 EventService.create 재사용(가시성/알림/색 동일). AI 는 DB 직접 안 씀.
 */
@Service
@RequiredArgsConstructor
public class AiScheduleService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final double CONFIDENCE_REVIEW_THRESHOLD = 0.6;
    private static final int MAX_CANDIDATES = 30;

    private final LlmExtractor extractor;
    private final AiImportLogRepository logRepo;
    private final AiCommitReceiptRepository receiptRepo;
    private final AiProperties props;
    private final EventService eventService;
    private final EventRepository eventRepository;

    // ─── 추출 ──────────────────────────────────────────────────────────────

    // @Transactional 없음 — LLM 호출(수~수십초)을 트랜잭션 밖에서 수행해 DB 커넥션 점유 방지.
    // logRepo.save 는 Spring Data 가 자체 트랜잭션으로 커밋.
    public ExtractResponse extract(Long userId, ExtractRequest req) {
        if (!extractor.isConfigured()) throw new CustomException(ErrorCode.AI_PROVIDER_NOT_CONFIGURED);

        String source = pickSource(req);
        if (!StringUtils.hasText(source)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "추출할 텍스트가 없어요");
        }

        int limit = props.getQuota().getDailyLimit();
        int used = usedToday(userId);
        if (used >= limit) throw new CustomException(ErrorCode.AI_QUOTA_EXCEEDED);

        LocalDate todayKst = LocalDate.now(KST);
        // null/true → 웹검색, false → 입력 텍스트만 구조화. 클라가 입력 구조(붙여넣기/검색요청)로 판단해 전달.
        boolean webSearch = req.search() == null || req.search();
        Extraction ex = extractor.extract(source, todayKst, webSearch);

        List<EventCandidate> candidates = new ArrayList<>();
        boolean truncated = false;
        int i = 0;
        for (Extraction.RawCandidate rc : ex.candidates()) {
            if (candidates.size() >= MAX_CANDIDATES) { truncated = true; break; }
            EventCandidate c = normalize(rc, "c" + (++i), todayKst);
            if (c != null) candidates.add(c);
        }

        List<ExtractResponse.Duplicate> dups = findDuplicates(userId, candidates);
        List<ExtractResponse.Source> sources = ex.sources().stream()
                .map(sc -> new ExtractResponse.Source(sc.title(), sc.url()))
                .toList();

        AiImportLog logEntry = logRepo.save(AiImportLog.ofExtract(userId));

        return new ExtractResponse(
                logEntry.getId(), candidates, dups, sources, ex.model(),
                new ExtractResponse.Usage(ex.inputTokens(), ex.outputTokens()),
                new ExtractResponse.Quota(used + 1, limit),
                truncated);
    }

    private String pickSource(ExtractRequest req) {
        if (StringUtils.hasText(req.text())) return req.text().trim();
        // url 은 Phase 2(SSRF 가드 fetch) — 현재 미지원이라 무시.
        if (StringUtils.hasText(req.prompt())) return req.prompt().trim();
        return null;
    }

    // ─── 검증/정규화 (§5) ──────────────────────────────────────────────────

    /** 원시 후보 → 검증/정규화된 후보. 파싱 불가면 null(드롭). */
    private EventCandidate normalize(Extraction.RawCandidate rc, String tempId, LocalDate todayKst) {
        if (rc == null || !StringUtils.hasText(rc.title())) return null;
        Instant start = parseKst(rc.startAt());
        if (start == null) return null;                       // 날짜 못 잡으면 드롭(헛 추출 방지)
        Instant end = parseKst(rc.endAt());
        if (end == null) end = start;
        boolean allDay = rc.allDay();

        if (allDay) {
            LocalDate sd = start.atZone(KST).toLocalDate();
            LocalDate ed = end.atZone(KST).toLocalDate();
            if (ed.isBefore(sd)) ed = sd;
            start = sd.atStartOfDay(KST).toInstant();
            end = ed.atTime(23, 59, 59).atZone(KST).toInstant();
        } else if (end.isBefore(start)) {
            end = start;
        }

        double confidence = Math.max(0.0, Math.min(1.0, rc.confidence()));
        boolean needsReview = rc.needsReview() || confidence < CONFIDENCE_REVIEW_THRESHOLD;
        String reason = null;

        // 범위 가드 — 너무 먼 미래/과거는 확인 유도
        LocalDate startDay = start.atZone(KST).toLocalDate();
        if (startDay.isAfter(todayKst.plusYears(2))) { needsReview = true; reason = "날짜가 2년 이상 미래 — 확인 필요"; }
        else if (startDay.isBefore(todayKst.minusYears(1))) { needsReview = true; reason = "날짜가 1년 이상 과거 — 확인 필요"; }
        // 상대 날짜("다음 주 수요일" 등)는 모델 해석이 문맥따라 불안정(스모크 테스트서 오해석 확인) → 확인 유도
        if (containsRelativeDate(rc.sourceQuote())) {
            needsReview = true;
            if (reason == null) reason = "상대 날짜 표현 — 날짜 확인 필요";
        }
        if (reason == null && needsReview) {
            reason = rc.needsReview() ? "AI 확인 요청" : "신뢰도 낮음";
        }

        return new EventCandidate(
                tempId,
                cap(rc.title(), 100),
                start, end, allDay,
                cap(rc.location(), 200),
                null,                                          // memo: 사용자가 편집/추가
                confidence, needsReview, reason,
                cap(rc.sourceQuote(), 500));
    }

    private Instant parseKst(String s) {
        if (!StringUtils.hasText(s)) return null;
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignore) { }
        try { return LocalDateTime.parse(s).atZone(KST).toInstant(); } catch (Exception ignore) { }
        try { return LocalDate.parse(s).atStartOfDay(KST).toInstant(); } catch (Exception ignore) { }
        return null;
    }

    private static final String[] RELATIVE_DATE_HINTS = {
            "내일", "모레", "글피", "다음 주", "다음주", "이번 주", "이번주", "다다음",
            "다음 달", "다음달", "이번 달", "이번달", "주말", "평일", "곧", "조만간"
    };

    /** 상대 날짜 표현이 근거에 있으면 모델 해석이 불안정 → needsReview 강제 대상. */
    private static boolean containsRelativeDate(String quote) {
        if (quote == null) return false;
        for (String h : RELATIVE_DATE_HINTS) if (quote.contains(h)) return true;
        return false;
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ─── 중복 감지 ─────────────────────────────────────────────────────────

    private List<ExtractResponse.Duplicate> findDuplicates(Long userId, List<EventCandidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        Instant from = candidates.stream().map(EventCandidate::startAt).min(Comparator.naturalOrder()).orElseThrow();
        Instant to = candidates.stream().map(EventCandidate::endAt).max(Comparator.naturalOrder()).orElseThrow();
        List<Event> existing = eventRepository.findVisibleEventsInRange(userId, from.minusSeconds(86400), to.plusSeconds(86400));
        if (existing.isEmpty()) return List.of();

        List<ExtractResponse.Duplicate> dups = new ArrayList<>();
        for (EventCandidate c : candidates) {
            LocalDate cDay = c.startAt().atZone(KST).toLocalDate();
            for (Event e : existing) {
                LocalDate eDay = e.getStartAt().atZone(KST).toLocalDate();
                if (cDay.equals(eDay) && similarTitle(c.title(), e.getTitle())) {
                    dups.add(new ExtractResponse.Duplicate(c.tempId(), e.getId(), e.getTitle()));
                    break;
                }
            }
        }
        return dups;
    }

    private static boolean similarTitle(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.replaceAll("\\s+", "").toLowerCase();
        String y = b.replaceAll("\\s+", "").toLowerCase();
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        // 짧은 제목은 포함(substring) 매칭 제외 — '회의'가 '팀정기회의'에 오탐되는 것 방지.
        if (x.length() < 4 || y.length() < 4) return false;
        return x.contains(y) || y.contains(x);
    }

    // ─── commit (선택분만 저장) ─────────────────────────────────────────────

    @Transactional
    public CommitResponse commit(Long userId, CommitRequest req) {
        // 멱등성 — 같은 (user, key) 의 재요청(타임아웃 후 재탭)은 저장된 결과를 그대로 반환 (P1-1).
        // 진짜 동시 중복은 uq_ai_commit_user_key 가 한쪽을 막는다(그쪽 재시도는 영수증을 받음).
        if (StringUtils.hasText(req.idempotencyKey())) {
            var receipt = receiptRepo.findByUserIdAndIdemKey(userId, req.idempotencyKey().trim());
            if (receipt.isPresent()) {
                return new CommitResponse(receipt.get().eventIdList(), receipt.get().getRequested());
            }
        }
        List<Long> ids = new ArrayList<>();
        for (CommitRequest.CommitEvent ce : req.events()) {
            EventCreateRequest cr = new EventCreateRequest(
                    ce.title(), ce.startAt(), ce.endAt(), ce.location(), ce.memo(),
                    null,                 // colorKey — EventService 가 본인 색 계산
                    req.groupIds(),
                    null, null, null,     // pollId, shadowOfEventId, rrule
                    null, null, null, null, // latitude, longitude, placeName, roadAddress
                    ce.allDay(),
                    null, null);          // reminderMinutesBefore, reminderAllDayDays
            EventResponse r = eventService.create(userId, cr, true);  // 일반 일정처럼 건별 알림("○○ 일정이 추가됐어요", 제목 포함)
            ids.add(r.id());
        }
        if (StringUtils.hasText(req.idempotencyKey())) {
            receiptRepo.save(new AiCommitReceipt(userId, req.idempotencyKey().trim(), ids, req.events().size()));
        }
        return new CommitResponse(ids, req.events().size());
    }

    // ─── quota ─────────────────────────────────────────────────────────────

    public QuotaResponse quota(Long userId) {
        return new QuotaResponse(usedToday(userId), props.getQuota().getDailyLimit(), startOfTomorrowKst());
    }

    private int usedToday(Long userId) {
        return logRepo.countByUserIdAndCreatedAtGreaterThanEqual(userId, startOfTodayKst());
    }

    private Instant startOfTodayKst() {
        return LocalDate.now(KST).atStartOfDay(KST).toInstant();
    }

    private Instant startOfTomorrowKst() {
        return LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();
    }
}

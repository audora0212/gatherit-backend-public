package com.gatherit.api.ai.service;

import com.gatherit.api.ai.config.AiProperties;
import com.gatherit.api.ai.dto.CommitRequest;
import com.gatherit.api.ai.dto.CommitResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiScheduleService 단위 테스트.
 * 실제 LLM 호출 없이 GeminiExtractor(LlmExtractor)를 Mock 으로 대체해 전 분기 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiScheduleServiceTest {

    private static final long USER = 1L;
    private static final long LOG_ID = 100L;
    private static final long GROUP_ID = 10L;
    private static final long EVENT_ID = 50L;

    @Mock LlmExtractor extractor;
    @Mock AiImportLogRepository logRepo;
    @Mock AiCommitReceiptRepository receiptRepo;
    @Mock AiProperties props;
    @Mock AiProperties.Quota quota;
    @Mock EventService eventService;
    @Mock EventRepository eventRepository;

    AiScheduleService service;

    // ── helpers ──────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        service = new AiScheduleService(extractor, logRepo, receiptRepo, props, eventService, eventRepository);

        // 기본 설정: 설정 완료, 하루 한도 10회, 오늘 0회 사용
        when(extractor.isConfigured()).thenReturn(true);
        when(props.getQuota()).thenReturn(quota);
        when(quota.getDailyLimit()).thenReturn(10);
        when(logRepo.countByUserIdAndCreatedAtGreaterThanEqual(eq(USER), any(Instant.class)))
                .thenReturn(0);

        // logRepo.save → 로그 엔티티(id 세팅) 반환
        when(logRepo.save(any(AiImportLog.class))).thenAnswer(inv -> {
            AiImportLog log = inv.getArgument(0);
            ReflectionTestUtils.setField(log, "id", LOG_ID);
            return log;
        });

        // eventRepository 기본: 범위 내 기존 일정 없음
        when(eventRepository.findVisibleEventsInRange(anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    private ExtractRequest textReq(String text) {
        return new ExtractRequest(null, text, null, false, null);
    }

    private ExtractRequest promptReq(String prompt) {
        return new ExtractRequest(prompt, null, null, false, null);
    }

    private Extraction extraction(List<Extraction.RawCandidate> candidates) {
        return new Extraction(candidates, List.of(), 1000, 500, "gemini-2.5-flash-lite");
    }

    private Extraction.RawCandidate candidate(String title, String startAt) {
        return new Extraction.RawCandidate(title, startAt, startAt, false, null, 0.9, false, null);
    }

    private EventResponse stubEventResponse(long id) {
        EventResponse r = new EventResponse(
                id, "제목", Instant.now(), Instant.now(), null, null, null,
                USER, List.of(GROUP_ID), null, null, List.of(), List.of(),
                null, null, null, null, null, false, null, null, null, null,
                List.of(), null, null);
        return r;
    }

    private CommitRequest commitReq(String idemKey, Long importLogId, List<Long> groupIds,
                                    List<CommitRequest.CommitEvent> events) {
        return new CommitRequest(groupIds, events, importLogId, idemKey);
    }

    private CommitRequest.CommitEvent commitEvent(String title) {
        Instant now = Instant.parse("2026-06-22T09:00:00Z");
        Instant end = Instant.parse("2026-06-22T10:00:00Z");
        return new CommitRequest.CommitEvent(title, now, end, false, null, null);
    }

    // ── extract: 설정 미완료 ──────────────────────────────────────────────────

    @Test
    @DisplayName("extract — extractor.isConfigured()=false → AI_PROVIDER_NOT_CONFIGURED")
    void extract_notConfigured() {
        when(extractor.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.extract(USER, textReq("대학교 축제 5월 10일")))
                .isInstanceOf(CustomException.class)
                .extracting(AiScheduleServiceTest::codeOf)
                .isEqualTo(ErrorCode.AI_PROVIDER_NOT_CONFIGURED);
    }

    // ── extract: 입력 없음 ────────────────────────────────────────────────────

    @Test
    @DisplayName("extract — text/prompt 모두 없으면 INVALID_INPUT")
    void extract_emptySource() {
        ExtractRequest req = new ExtractRequest(null, null, null, false, null);

        assertThatThrownBy(() -> service.extract(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(AiScheduleServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("extract — text 가 공백만이면 prompt fallback 없으면 INVALID_INPUT")
    void extract_blankText_noPrompt() {
        ExtractRequest req = new ExtractRequest(null, "   ", null, false, null);

        assertThatThrownBy(() -> service.extract(USER, req))
                .isInstanceOf(CustomException.class)
                .extracting(AiScheduleServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── extract: 쿼터 초과 ────────────────────────────────────────────────────

    @Test
    @DisplayName("extract — 오늘 사용 횟수가 한도 이상이면 AI_QUOTA_EXCEEDED")
    void extract_quotaExceeded() {
        when(logRepo.countByUserIdAndCreatedAtGreaterThanEqual(eq(USER), any(Instant.class)))
                .thenReturn(10);

        assertThatThrownBy(() -> service.extract(USER, textReq("행사 텍스트")))
                .isInstanceOf(CustomException.class)
                .extracting(AiScheduleServiceTest::codeOf)
                .isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
    }

    // ── extract: 정상 흐름 ────────────────────────────────────────────────────

    @Test
    @DisplayName("extract — 정상 추출: 후보 반환, importLogId 포함, quota usedToday+1")
    void extract_success() {
        Extraction ex = extraction(List.of(
                candidate("MT 행사", "2026-08-01T09:00:00+09:00")
        ));
        when(extractor.extract(anyString(), any(), eq(false))).thenReturn(ex);

        ExtractResponse resp = service.extract(USER, textReq("MT 8월 1일 9시"));

        assertThat(resp.importLogId()).isEqualTo(LOG_ID);
        assertThat(resp.candidates()).hasSize(1);
        assertThat(resp.candidates().get(0).title()).isEqualTo("MT 행사");
        assertThat(resp.quota().usedToday()).isEqualTo(1);
        assertThat(resp.quota().dailyLimit()).isEqualTo(10);
        assertThat(resp.duplicates()).isEmpty();
        assertThat(resp.truncated()).isFalse();

        verify(logRepo).save(any(AiImportLog.class));
    }

    @Test
    @DisplayName("extract — prompt 우선순위: text 없으면 prompt 를 source 로 사용")
    void extract_promptFallback() {
        Extraction ex = extraction(List.of());
        when(extractor.extract(anyString(), any(), eq(false))).thenReturn(ex);

        ExtractResponse resp = service.extract(USER, promptReq("대학 행사 일정 추출해줘"));

        assertThat(resp).isNotNull();
        verify(extractor).extract(eq("대학 행사 일정 추출해줘"), any(), eq(false));
    }

    @Test
    @DisplayName("extract — text 와 prompt 모두 있으면 text 우선")
    void extract_textTakesPriorityOverPrompt() {
        Extraction ex = extraction(List.of());
        when(extractor.extract(anyString(), any(), eq(false))).thenReturn(ex);

        ExtractRequest req = new ExtractRequest("설명 프롬프트", "본문 텍스트", null, false, null);
        service.extract(USER, req);

        verify(extractor).extract(eq("본문 텍스트"), any(), eq(false));
    }

    @Test
    @DisplayName("extract — search=null 이면 webSearch=true 로 extractor 호출")
    void extract_searchNull_usesWebSearch() {
        Extraction ex = extraction(List.of());
        when(extractor.extract(anyString(), any(), eq(true))).thenReturn(ex);

        ExtractRequest req = new ExtractRequest(null, "행사 텍스트", null, null, null);
        service.extract(USER, req);

        verify(extractor).extract(anyString(), any(), eq(true));
    }

    @Test
    @DisplayName("extract — search=true 이면 webSearch=true 로 extractor 호출")
    void extract_searchTrue_usesWebSearch() {
        Extraction ex = extraction(List.of());
        when(extractor.extract(anyString(), any(), eq(true))).thenReturn(ex);

        ExtractRequest req = new ExtractRequest(null, "행사 텍스트", null, true, null);
        service.extract(USER, req);

        verify(extractor).extract(anyString(), any(), eq(true));
    }

    // ── extract: 빈 결과 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("extract — 추출 결과 후보 없으면 빈 candidates 반환(예외 없음)")
    void extract_emptyResult() {
        when(extractor.extract(anyString(), any(), eq(false))).thenReturn(extraction(List.of()));

        ExtractResponse resp = service.extract(USER, textReq("일정 없는 텍스트"));

        assertThat(resp.candidates()).isEmpty();
        assertThat(resp.importLogId()).isEqualTo(LOG_ID);
    }

    // ── extract: 검증 실패 후보 드롭 ──────────────────────────────────────────

    @Test
    @DisplayName("extract — 제목 없는 후보는 드롭")
    void extract_candidateNoTitle_dropped() {
        Extraction.RawCandidate noTitle = new Extraction.RawCandidate(
                "", "2026-08-01T09:00:00+09:00", "2026-08-01T10:00:00+09:00",
                false, null, 0.9, false, null);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(noTitle)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).isEmpty();
    }

    @Test
    @DisplayName("extract — 날짜 파싱 불가 후보는 드롭")
    void extract_candidateUnparseableDate_dropped() {
        Extraction.RawCandidate bad = new Extraction.RawCandidate(
                "의심스러운 행사", "언젠가쯤", null, false, null, 0.9, false, null);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(bad)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).isEmpty();
    }

    @Test
    @DisplayName("extract — confidence < 0.6 후보는 needsReview=true 로 정규화")
    void extract_lowConfidence_markedNeedsReview() {
        Extraction.RawCandidate low = new Extraction.RawCandidate(
                "저신뢰 행사", "2026-08-01T09:00:00+09:00", "2026-08-01T10:00:00+09:00",
                false, null, 0.3, false, null);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(low)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).hasSize(1);
        assertThat(resp.candidates().get(0).needsReview()).isTrue();
    }

    @Test
    @DisplayName("extract — 2년 이상 미래 날짜는 needsReview=true + reason 포함")
    void extract_farFutureDate_markedNeedsReview() {
        Extraction.RawCandidate future = new Extraction.RawCandidate(
                "먼 미래 행사", "2030-01-01T09:00:00+09:00", "2030-01-01T10:00:00+09:00",
                false, null, 0.95, false, null);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(future)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).hasSize(1);
        assertThat(resp.candidates().get(0).needsReview()).isTrue();
        assertThat(resp.candidates().get(0).reviewReason()).contains("2년");
    }

    @Test
    @DisplayName("extract — 1년 이상 과거 날짜는 needsReview=true + reason 포함")
    void extract_farPastDate_markedNeedsReview() {
        Extraction.RawCandidate past = new Extraction.RawCandidate(
                "과거 행사", "2020-01-01T09:00:00+09:00", "2020-01-01T10:00:00+09:00",
                false, null, 0.95, false, null);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(past)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).hasSize(1);
        assertThat(resp.candidates().get(0).needsReview()).isTrue();
        assertThat(resp.candidates().get(0).reviewReason()).contains("과거");
    }

    @Test
    @DisplayName("extract — sourceQuote 에 상대 날짜 표현 포함 시 needsReview=true")
    void extract_relativeDate_markedNeedsReview() {
        Extraction.RawCandidate relative = new Extraction.RawCandidate(
                "다음 주 회의", "2026-06-29T09:00:00+09:00", "2026-06-29T10:00:00+09:00",
                false, null, 0.95, false, "다음 주 수요일에 회의가 있어요");
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(relative)));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates()).hasSize(1);
        assertThat(resp.candidates().get(0).needsReview()).isTrue();
    }

    @Test
    @DisplayName("extract — 30개 초과 후보는 상한 잘라 truncated=true")
    void extract_moreThan30Candidates_truncated() {
        List<Extraction.RawCandidate> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            many.add(candidate("행사" + i, "2026-08-0" + (i <= 9 ? i : "9") + "T09:00:00+09:00"));
        }
        // 유효 날짜 35개 — 파싱되는 것만: 1~9일까지 9개 + 이후는 같은 날짜 반복
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(new Extraction(many, List.of(), 1000, 500, "gemini-2.5-flash-lite"));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.candidates().size()).isLessThanOrEqualTo(30);
        assertThat(resp.truncated()).isTrue();
    }

    // ── extract: 중복 감지 ────────────────────────────────────────────────────

    @Test
    @DisplayName("extract — 같은 날 같은 제목 기존 일정 있으면 duplicate 반환")
    void extract_duplicateDetected() {
        String startAt = "2026-08-01T09:00:00+09:00";
        Extraction.RawCandidate rc = candidate("팀 회의", startAt);
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenReturn(extraction(List.of(rc)));

        Event existing = Event.builder()
                .title("팀 회의")
                .startAt(Instant.parse("2026-08-01T00:00:00Z"))
                .endAt(Instant.parse("2026-08-01T01:00:00Z"))
                .createdBy(USER)
                .build();
        ReflectionTestUtils.setField(existing, "id", EVENT_ID);

        when(eventRepository.findVisibleEventsInRange(anyLong(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(existing));

        ExtractResponse resp = service.extract(USER, textReq("텍스트"));

        assertThat(resp.duplicates()).hasSize(1);
        assertThat(resp.duplicates().get(0).existingEventId()).isEqualTo(EVENT_ID);
        assertThat(resp.duplicates().get(0).existingTitle()).isEqualTo("팀 회의");
    }

    // ── extract: extractor 예외 처리 ──────────────────────────────────────────

    @Test
    @DisplayName("extract — extractor 가 CustomException 던지면 사용량 기록 없이 오류 전달")
    void extract_extractorThrowsCustomException() {
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenThrow(new CustomException(ErrorCode.AI_EXTRACTION_FAILED));

        assertThatThrownBy(() -> service.extract(USER, textReq("텍스트")))
                .isInstanceOf(CustomException.class)
                .extracting(AiScheduleServiceTest::codeOf)
                .isEqualTo(ErrorCode.AI_EXTRACTION_FAILED);

        verify(logRepo, never()).save(any(AiImportLog.class));
    }

    @Test
    @DisplayName("extract — extractor 가 RuntimeException 던지면 사용량 기록 없이 오류 전달")
    void extract_extractorThrowsRuntimeException() {
        when(extractor.extract(anyString(), any(), eq(false)))
                .thenThrow(new RuntimeException("LLM 네트워크 오류"));

        assertThatThrownBy(() -> service.extract(USER, textReq("텍스트")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM 네트워크 오류");

        verify(logRepo, never()).save(any(AiImportLog.class));
    }

    // ── quota ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("quota — 오늘 사용 수/한도/리셋 시각 반환")
    void quota_returnsCorrectValues() {
        when(logRepo.countByUserIdAndCreatedAtGreaterThanEqual(eq(USER), any(Instant.class)))
                .thenReturn(3);

        QuotaResponse resp = service.quota(USER);

        assertThat(resp.usedToday()).isEqualTo(3);
        assertThat(resp.dailyLimit()).isEqualTo(10);
        assertThat(resp.resetAt()).isNotNull();
    }

    // ── commit: 멱등성 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("commit — 같은 idempotencyKey 재요청 시 영수증 재반환, EventService 미호출")
    void commit_idempotent_returnsReceipt() {
        String idemKey = "idem-key-001";
        AiCommitReceipt receipt = new AiCommitReceipt(USER, idemKey, List.of(EVENT_ID), 1);

        when(receiptRepo.findByUserIdAndIdemKey(USER, idemKey)).thenReturn(Optional.of(receipt));

        CommitRequest req = commitReq(idemKey, null, List.of(GROUP_ID), List.of(commitEvent("행사")));
        CommitResponse resp = service.commit(USER, req);

        assertThat(resp.createdEventIds()).containsExactly(EVENT_ID);
        assertThat(resp.requested()).isEqualTo(1);
        verify(eventService, never()).create(anyLong(), any(EventCreateRequest.class), eq(true));
    }

    // ── commit: 정상 저장 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("commit — 정상 커밋: EventService.create 호출 + 영수증 저장 + CommitResponse 반환")
    void commit_success() {
        when(receiptRepo.findByUserIdAndIdemKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(eventService.create(anyLong(), any(EventCreateRequest.class), eq(true)))
                .thenReturn(stubEventResponse(EVENT_ID));

        String idemKey = "idem-key-002";
        CommitRequest req = commitReq(idemKey, null, List.of(GROUP_ID), List.of(commitEvent("MT 행사")));
        CommitResponse resp = service.commit(USER, req);

        assertThat(resp.createdEventIds()).containsExactly(EVENT_ID);
        assertThat(resp.requested()).isEqualTo(1);

        verify(eventService).create(eq(USER), any(EventCreateRequest.class), eq(true));
        verify(receiptRepo).save(any(AiCommitReceipt.class));
    }

    @Test
    @DisplayName("commit — idempotencyKey 없으면 영수증 저장 안 함")
    void commit_noIdemKey_noReceiptSaved() {
        when(eventService.create(anyLong(), any(EventCreateRequest.class), eq(true)))
                .thenReturn(stubEventResponse(EVENT_ID));

        CommitRequest req = commitReq(null, null, List.of(GROUP_ID), List.of(commitEvent("행사")));
        service.commit(USER, req);

        verify(receiptRepo, never()).save(any(AiCommitReceipt.class));
    }

    @Test
    @DisplayName("commit — 기존 요청의 importLogId를 허용하며 사용량 기록은 변경하지 않음")
    void commit_acceptsLegacyImportLogIdWithoutChangingUsage() {
        when(eventService.create(eq(USER), any(EventCreateRequest.class), eq(true)))
                .thenReturn(stubEventResponse(EVENT_ID));

        CommitRequest req = commitReq(null, LOG_ID, List.of(GROUP_ID), List.of(commitEvent("행사")));
        assertThat(service.commit(USER, req).createdEventIds()).containsExactly(EVENT_ID);

        verifyNoInteractions(logRepo);
    }

    @Test
    @DisplayName("commit — 여러 이벤트 일괄 등록: 요청 수만큼 EventService.create 호출")
    void commit_multipleEvents() {
        when(eventService.create(anyLong(), any(EventCreateRequest.class), eq(true)))
                .thenReturn(stubEventResponse(EVENT_ID))
                .thenReturn(stubEventResponse(EVENT_ID + 1));

        List<CommitRequest.CommitEvent> events = List.of(
                commitEvent("행사1"),
                commitEvent("행사2")
        );
        CommitRequest req = commitReq(null, null, List.of(GROUP_ID), events);
        CommitResponse resp = service.commit(USER, req);

        assertThat(resp.requested()).isEqualTo(2);
        assertThat(resp.createdEventIds()).hasSize(2);

        ArgumentCaptor<EventCreateRequest> captor = ArgumentCaptor.forClass(EventCreateRequest.class);
        verify(eventService, org.mockito.Mockito.times(2))
                .create(eq(USER), captor.capture(), eq(true));
        List<EventCreateRequest> captured = captor.getAllValues();
        assertThat(captured.get(0).title()).isEqualTo("행사1");
        assertThat(captured.get(1).title()).isEqualTo("행사2");
    }

    @Test
    @DisplayName("commit — EventCreateRequest 에 groupIds 올바르게 전달")
    void commit_groupIdsPassedToEventService() {
        when(eventService.create(anyLong(), any(EventCreateRequest.class), eq(true)))
                .thenReturn(stubEventResponse(EVENT_ID));

        List<Long> groups = List.of(GROUP_ID, 20L);
        CommitRequest req = commitReq(null, null, groups, List.of(commitEvent("행사")));
        service.commit(USER, req);

        ArgumentCaptor<EventCreateRequest> captor = ArgumentCaptor.forClass(EventCreateRequest.class);
        verify(eventService).create(eq(USER), captor.capture(), eq(true));
        assertThat(captor.getValue().groupIds()).containsExactlyInAnyOrderElementsOf(groups);
    }
}

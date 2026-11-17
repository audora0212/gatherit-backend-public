package com.gatherit.api.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gatherit.api.auth.dto.AuthResponse;
import com.gatherit.api.auth.dto.RefreshRequest;
import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.event.dto.EditScope;
import com.gatherit.api.event.dto.EventCreateRequest;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.dto.EventUpdateRequest;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.poll.dto.PollCreateRequest;
import com.gatherit.api.poll.dto.PublicPollResponse;
import com.gatherit.api.poll.dto.PublicPollVoteRequest;
import com.gatherit.api.poll.dto.VoteRequest;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.security.JwtAuthenticationEntryPoint;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Jackson 보안 패치 때 실제 HTTP DTO의 JSON 계약을 확인한다.
 * 테스트 전용 mapper 옵션을 만들지 않고 Spring Boot의 JSON 자동 구성을 그대로 사용한다.
 * DB, 외부 OAuth, 운영 서버를 호출하지 않는다.
 */
@JsonTest
@ImportAutoConfiguration(Jackson2AutoConfiguration.class)
@ActiveProfiles("json-contract")
class JacksonSecurityCompatibilityTest {

    private static final String TEST_SIGNING_KEY = "json-contract-test-only-key-at-least-32-bytes";

    @Autowired
    private ObjectMapper mapper;

    @Test
    void bootMapperKeepsIsoDatesAndForwardCompatibleRequests() {
        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    }

    @Test
    void timedEventAcceptsOffsetAndFractionalSecondsWithoutLosingTheInstant() throws Exception {
        EventCreateRequest request = mapper.readValue("""
                {
                  "title":"스터디 ☕", "startAt":"2026-11-02T09:30:00.123+09:00",
                  "endAt":"2026-11-02T10:30:00+09:00", "groupIds":[21,22],
                  "colorKey":"team", "allDay":false, "futureClientField":{"ignored":true}
                }
                """, EventCreateRequest.class);

        assertThat(request.title()).isEqualTo("스터디 ☕");
        assertThat(request.startAt()).isEqualTo(Instant.parse("2026-11-02T00:30:00.123Z"));
        assertThat(request.endAt()).isEqualTo(Instant.parse("2026-11-02T01:30:00Z"));
        assertThat(request.groupIds()).containsExactly(21L, 22L);
        assertThat(request.colorKey()).isEqualTo(GroupColor.team);
        assertThat(request.allDay()).isFalse();
        JsonNode json = mapper.valueToTree(request);
        assertThat(json.path("startAt").textValue()).isEqualTo("2026-11-02T00:30:00.123Z");
        assertThat(json.path("endAt").textValue()).isEqualTo("2026-11-02T01:30:00Z");
    }

    @Test
    void allDayEventRetainsClientNormalizedKoreanDayAndReminderSentinels() throws Exception {
        EventCreateRequest request = mapper.readValue("""
                {
                  "title":"종일 일정", "startAt":"2026-11-01T15:00:00Z",
                  "endAt":"2026-11-02T14:59:00Z", "groupIds":[21], "allDay":true,
                  "reminderMinutesBefore":0, "reminderAllDayDays":-1
                }
                """, EventCreateRequest.class);

        assertThat(request.startAt()).isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
        assertThat(request.endAt()).isEqualTo(Instant.parse("2026-11-02T14:59:00Z"));
        assertThat(request.allDay()).isTrue();
        assertThat(request.reminderMinutesBefore()).isZero();
        assertThat(request.reminderAllDayDays()).isEqualTo(-1);
        assertThat(mapper.valueToTree(request).path("allDay").booleanValue()).isTrue();
    }

    @Test
    void legacyEventCreateKeepsMissingOptionalRecordFieldsNull() throws Exception {
        EventCreateRequest request = mapper.readValue("""
                {"title":"기존 앱 일정","startAt":"2026-11-02T00:00:00Z",
                 "endAt":"2026-11-02T01:00:00Z","groupIds":[21]}
                """, EventCreateRequest.class);

        assertThat(request.allDay()).isNull();
        assertThat(request.colorKey()).isNull();
        assertThat(request.pollId()).isNull();
        assertThat(request.shadowOfEventId()).isNull();
        assertThat(request.rrule()).isNull();
        assertThat(request.latitude()).isNull();
        assertThat(request.reminderMinutesBefore()).isNull();
        assertThat(request.reminderAllDayDays()).isNull();
    }

    @Test
    void recurrenceUpdateKeepsScopeOccurrenceAndResetSentinel() throws Exception {
        EventUpdateRequest request = mapper.readValue("""
                {"rrule":"FREQ=WEEKLY;BYDAY=MO;COUNT=8","editScope":"THIS_AND_FUTURE",
                 "occurrenceStart":"2026-11-09T09:00:00+09:00","clearGeo":true,
                 "reminderMinutesBefore":-2147483648,"reminderAllDayDays":0}
                """, EventUpdateRequest.class);

        assertThat(request.editScope()).isEqualTo(EditScope.THIS_AND_FUTURE);
        assertThat(request.occurrenceStart()).isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
        assertThat(request.rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO;COUNT=8");
        assertThat(request.clearGeo()).isTrue();
        assertThat(request.reminderMinutesBefore()).isEqualTo(Integer.MIN_VALUE);
        assertThat(request.reminderAllDayDays()).isZero();
        assertThat(request.groupIds()).isNull();
    }

    @Test
    void partialUpdateDistinguishesOmittedValuesFromExplicitClearAndFalse() throws Exception {
        EventUpdateRequest omitted = mapper.readValue("{}", EventUpdateRequest.class);
        EventUpdateRequest explicit = mapper.readValue("""
                {"rrule":"","placeName":"","groupIds":[],"allDay":false,"clearGeo":false}
                """, EventUpdateRequest.class);

        assertThat(omitted.rrule()).isNull();
        assertThat(omitted.allDay()).isNull();
        assertThat(omitted.editScope()).isNull();
        assertThat(omitted.groupIds()).isNull();
        assertThat(explicit.rrule()).isEmpty();
        assertThat(explicit.placeName()).isEmpty();
        assertThat(explicit.groupIds()).isEmpty();
        assertThat(explicit.allDay()).isFalse();
        assertThat(explicit.clearGeo()).isFalse();
    }

    @Test
    void pollCreationKeepsNestedRecordCandidatesAndPolicyNames() throws Exception {
        PollCreateRequest request = mapper.readValue("""
                {"groupId":21,"title":"날짜 투표","deadlineAt":"2026-11-10T23:59:00+09:00",
                 "confirmPolicy":"PROPOSER_ONLY","slots":[
                   {"startAt":"2026-11-11T09:00:00+09:00","endAt":"2026-11-11T09:10:00+09:00"},
                   {"startAt":"2026-11-12T00:00:00Z","endAt":"2026-11-12T00:10:00Z"}
                 ]}
                """, PollCreateRequest.class);

        assertThat(request.confirmPolicy()).isEqualTo(ConfirmPolicy.PROPOSER_ONLY);
        assertThat(request.deadlineAt()).isEqualTo(Instant.parse("2026-11-10T14:59:00Z"));
        assertThat(request.slots()).hasSize(2);
        assertThat(request.slots().get(0).startAt()).isEqualTo(Instant.parse("2026-11-11T00:00:00Z"));
        JsonNode json = mapper.valueToTree(request);
        assertThat(json.path("confirmPolicy").textValue()).isEqualTo("PROPOSER_ONLY");
        assertThat(json.path("slots").get(0).path("endAt").textValue()).isEqualTo("2026-11-11T00:10:00Z");
        assertThat(mapper.readValue(mapper.writeValueAsString(request), PollCreateRequest.class)).isEqualTo(request);
    }

    @Test
    void votesKeepFalseResponsesAndPublicSlotIdSetSemantics() throws Exception {
        VoteRequest vote = mapper.readValue("""
                {"votes":[{"slotId":7,"available":false},{"slotId":9,"available":true}]}
                """, VoteRequest.class);
        PublicPollVoteRequest guest = mapper.readValue("""
                {"nickname":"연습 사용자","availableSlotIds":[7,9,7],"newClientField":"ignored"}
                """, PublicPollVoteRequest.class);

        assertThat(vote.votes()).containsExactly(new VoteRequest.SlotVote(7L, false), new VoteRequest.SlotVote(9L, true));
        assertThat(guest.availableSlotIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(guest.participantToken()).isNull();
        assertThat(mapper.valueToTree(vote).path("votes").get(0).path("available").booleanValue()).isFalse();
    }

    @Test
    void enumCaseAndUnknownValuesRemainStrict() throws Exception {
        assertThat(mapper.readValue("\"team\"", GroupColor.class)).isEqualTo(GroupColor.team);
        assertThat(mapper.readValue("\"ANYONE\"", ConfirmPolicy.class)).isEqualTo(ConfirmPolicy.ANYONE);
        assertThatThrownBy(() -> mapper.readValue("\"TEAM\"", GroupColor.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("\"anyone\"", ConfirmPolicy.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> mapper.readValue("\"FUTURE_UNKNOWN_SCOPE\"", EditScope.class)).isInstanceOf(JsonMappingException.class);
    }

    @Test
    void recurrenceResponseKeepsIsoExceptionDatesAndNestedShadows() throws Exception {
        Instant start = Instant.parse("2026-11-02T00:00:00Z");
        EventResponse response = new EventResponse(
                100L, "반복 일정", start, start.plusSeconds(3600), null, null,
                GroupColor.team, 42L, List.of(21L), null, null, List.of(22L),
                List.of(new EventResponse.ShadowSummary(101L, "다른 그룹에도 알림", List.of(22L))),
                "FREQ=WEEKLY;BYDAY=MO", null, null, null, null, false, null, 0,
                "연습 사용자", "#A699FF", List.of(start.plusSeconds(7 * 86400)), 90L, start);

        JsonNode json = mapper.valueToTree(ApiResponse.ok(response));
        assertThat(json.path("data").path("exdates").get(0).textValue()).isEqualTo("2026-11-09T00:00:00Z");
        assertThat(json.path("data").path("recurrenceId").textValue()).isEqualTo("2026-11-02T00:00:00Z");
        assertThat(json.path("data").path("myShadows").get(0).path("groupIds").get(0).longValue()).isEqualTo(22L);
        assertThat(mapper.readValue(mapper.writeValueAsString(response), EventResponse.class)).isEqualTo(response);
    }

    @Test
    void authRecordKeepsIsNewUserAndRoleInsideSuccessEnvelope() throws Exception {
        AuthResponse response = new AuthResponse("test-access", "test-refresh",
                new AuthResponse.UserSummary(42L, "user@example.test", "연습 사용자", null,
                        OauthProvider.GOOGLE, null, UserRole.USER), true);

        JsonNode json = mapper.valueToTree(ApiResponse.ok(response));
        assertThat(fieldNames(json)).containsExactlyInAnyOrder("success", "data");
        assertThat(json.path("success").booleanValue()).isTrue();
        assertThat(json.path("data").path("isNewUser").booleanValue()).isTrue();
        assertThat(json.path("data").has("newUser")).isFalse();
        assertThat(json.path("data").path("user").path("role").textValue()).isEqualTo("USER");
        assertThat(json.path("data").path("user").path("oauthProvider").textValue()).isEqualTo("GOOGLE");
        assertThat(json.path("data").path("user").path("personalColor").isNull()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(response), AuthResponse.class)).isEqualTo(response);
        assertThat(mapper.readValue("{\"refreshToken\":\"test-refresh\"}", RefreshRequest.class).refreshAttemptId()).isNull();
    }

    @Test
    void permissionErrorAndEmptySuccessRetainExistingEnvelopeFields() throws Exception {
        JsonNode forbidden = mapper.valueToTree(ApiResponse.error(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage()));
        assertThat(forbidden).isEqualTo(mapper.readTree("""
                {"success":false,"code":"C003","message":"권한이 없습니다"}
                """));
        assertThat(fieldNames(mapper.valueToTree(ApiResponse.ok()))).containsExactly("success");

        // 보안 필터는 Boot mapper 대신 실제 코드의 new ObjectMapper 경로를 사용한다.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint().commence(request, response,
                new InsufficientAuthenticationException("test-only missing authentication"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree("""
                {"success":false,"code":"C002","message":"인증이 필요합니다"}
                """));
    }

    @Test
    void publicPollResponseRetainsGuestPermissionFlagsAndCandidateFields() throws Exception {
        Instant start = Instant.parse("2026-11-11T00:00:00Z");
        PublicPollResponse response = new PublicPollResponse(
                50L, "test-only-share-token", 21L, "예시 그룹", false, true, "날짜 투표",
                start.minusSeconds(86400), null, false, start.plusSeconds(86400), 1, 0,
                List.of("연습 사용자"), "연습 사용자", List.of(7L),
                List.of(new PublicPollResponse.PublicSlot(7L, start, start.plusSeconds(600), 1, List.of("연습 사용자"))));

        JsonNode data = mapper.valueToTree(ApiResponse.ok(response)).path("data");
        assertThat(data.path("groupMember").booleanValue()).isFalse();
        assertThat(data.path("publicPollJoinAllowed").booleanValue()).isTrue();
        assertThat(data.path("closed").booleanValue()).isFalse();
        assertThat(data.path("confirmedSlotId").isNull()).isTrue();
        assertThat(data.path("myAvailableSlotIds").get(0).longValue()).isEqualTo(7L);
        assertThat(data.path("slots").get(0).path("startAt").textValue()).isEqualTo("2026-11-11T00:00:00Z");
        assertThat(mapper.readValue(mapper.writeValueAsString(response), PublicPollResponse.class)).isEqualTo(response);
    }

    @Test
    void javaTimeModuleRoundTripsLocalTimeAndLocalDateTimeWithoutInventingAnApiDto() throws Exception {
        // 현재 HTTP DTO에는 이 두 타입이 없다. 날짜 모듈 검증과 실제 DTO 계약을 구분한다.
        LocalTime time = LocalTime.of(9, 30, 15, 123_000_000);
        LocalDateTime dateTime = LocalDateTime.of(2026, 11, 2, 9, 30, 15, 123_000_000);
        assertThat(mapper.readTree(mapper.writeValueAsString(time)).isTextual()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(dateTime)).isTextual()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(time), LocalTime.class)).isEqualTo(time);
        assertThat(mapper.readValue(mapper.writeValueAsString(dateTime), LocalDateTime.class)).isEqualTo(dateTime);
    }

    @Test
    void jwtReaderAcceptsLegacySignedJsonNotProducedByTheCandidateJackson() throws Exception {
        JwtUtils jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "secret", TEST_SIGNING_KEY);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 365L * 86400 * 1000);
        jwt.init();
        long issuedAt = Instant.now().minusSeconds(60).getEpochSecond();
        long expiresAt = issuedAt + 365L * 86400;
        // 기존 refresh 형식처럼 token_use/email이 없는 JSON을 Jackson과 독립적으로 서명한다.
        String payload = "{\"sub\":\"42\",\"iat\":" + issuedAt + ",\"exp\":" + expiresAt + ",\"jti\":\"legacy-json-test\"}";
        String token = signRawJwt(payload);

        assertThat(jwt.validateRefreshToken(token)).isTrue();
        assertThat(jwt.getUserId(token)).isEqualTo(42L);
        assertThat(jwt.getExpiration(token)).isEqualTo(Instant.ofEpochSecond(expiresAt));
        assertThat(jwt.getEmail(token)).isNull();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String signRawJwt(String payload) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String encodedPayload = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String content = header + "." + encodedPayload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }
}

package com.gatherit.api.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter 단위 테스트.
 * Caffeine 실제 사용 — 외부 의존 없음.
 * MockHttpServletRequest/Response + MockFilterChain 사용.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * n회 요청을 실행하고 마지막 응답 상태 코드를 반환.
     * OncePerRequestFilter 는 request attribute 로 중복 실행을 막으므로
     * 매 호출마다 새 MockHttpServletRequest 인스턴스를 생성한다.
     */
    private int sendTimes(String method, String uri, String ip, int times) throws Exception {
        return sendTimes(method, uri, ip, null, times);
    }

    private int sendTimes(String method, String uri, String ip, String xForwardedFor, int times) throws Exception {
        int status = 200;
        for (int i = 0; i < times; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
            req.setRemoteAddr(ip);
            if (xForwardedFor != null) {
                req.addHeader("X-Forwarded-For", xForwardedFor);
            }
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter.doFilter(req, resp, new MockFilterChain());
            status = resp.getStatus();
        }
        return status;
    }

    /** 단일 요청 1회 실행 — 상태 코드 반환 */
    private int sendOnce(String method, String uri, String ip, String xForwardedFor) throws Exception {
        return sendOnce(method, uri, ip, xForwardedFor, null);
    }

    private int sendOnce(String method, String uri, String ip, String xForwardedFor, String xRealIp)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        if (xForwardedFor != null) {
            req.addHeader("X-Forwarded-For", xForwardedFor);
        }
        if (xRealIp != null) {
            req.addHeader("X-Real-IP", xRealIp);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp.getStatus();
    }

    private MockHttpServletResponse sendOnceResponse(String method, String uri, String ip, String xForwardedFor)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        if (xForwardedFor != null) {
            req.addHeader("X-Forwarded-For", xForwardedFor);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, new MockFilterChain());
        return resp;
    }

    // ── 1. 정상 통과 — 제한 이하 요청 ──────────────────────────────────────────

    @Test
    @DisplayName("login — 허용 횟수(5회) 이내는 200 통과")
    void login_withinLimit_pass() throws Exception {
        int status = sendTimes("POST", "/api/auth/login", "10.0.0.1", 5);
        assertThat(status).isEqualTo(200);
    }

    // ── 2. 429 반환 — 제한 초과 ──────────────────────────────────────────────

    @Test
    @DisplayName("login — 6번째 요청은 429 반환")
    void login_exceeded_429() throws Exception {
        int status = sendTimes("POST", "/api/auth/login", "10.0.0.2", 6);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("signup — 4번째 요청은 429 반환 (제한: 3회)")
    void signup_exceeded_429() throws Exception {
        int status = sendTimes("POST", "/api/auth/signup", "10.0.0.3", 4);
        assertThat(status).isEqualTo(429);
    }

    // ── 3. 엔드포인트별 차등 제한 ─────────────────────────────────────────────

    @Test
    @DisplayName("refresh — 허용 횟수(10회) 이내는 200 통과")
    void refresh_withinLimit_pass() throws Exception {
        int status = sendTimes("POST", "/api/auth/refresh", "10.0.0.4", 10);
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("refresh — 11번째 요청은 429 반환 (제한: 10회)")
    void refresh_exceeded_429() throws Exception {
        int status = sendTimes("POST", "/api/auth/refresh", "10.0.0.5", 11);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("groups join — 21번째 요청은 429 반환 (제한: 20회)")
    void groupsJoin_exceeded_429() throws Exception {
        int status = sendTimes("POST", "/api/groups/join", "10.0.0.6", 21);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("public poll handoff — 21번째 요청은 429 반환 (제한: 20회)")
    void publicPollHandoff_exceeded_429() throws Exception {
        int status = sendTimes(
                "POST", "/api/public/polls/0123456789abcdef0123456789abcdef/handoff",
                "10.0.0.7", 21);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("poll connection code claim — 11번째 요청은 429 반환 (제한: 10회)")
    void linkedPollCodeClaim_exceeded_429() throws Exception {
        int status = sendTimes("POST", "/api/polls/linked/claim-code", "10.0.0.8", 11);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("public poll 상세 GET — 허용(60회) 이내는 200 통과")
    void publicPollDetailGet_withinLimit_pass() throws Exception {
        int status = sendTimes(
                "GET", "/api/public/polls/0123456789abcdef0123456789abcdef", "10.0.0.9", 60);
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("public poll 상세 GET — 61번째 요청은 429 반환 (제한: 60회)")
    void publicPollDetailGet_exceeded_429() throws Exception {
        int status = sendTimes(
                "GET", "/api/public/polls/0123456789abcdef0123456789abcdef", "10.0.0.10", 61);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("GET 상세 규칙(/*)은 하위 경로(/vote)를 잡지 않음 — 세그먼트 격리")
    void publicPollDetailGet_doesNotMatchSubPath() throws Exception {
        int status = sendTimes(
                "GET", "/api/public/polls/0123456789abcdef0123456789abcdef/vote", "10.0.0.11", 100);
        assertThat(status).isEqualTo(200);
    }

    // ── 4. IP 파싱 — 신뢰할 수 있는 로컬 프록시 헤더만 사용 ──────────────────

    @Test
    @DisplayName("로컬 프록시의 X-Real-IP를 우선 사용해 조작된 X-Forwarded-For를 무시")
    void localProxy_xRealIpUsed() throws Exception {
        for (int i = 0; i < 5; i++) {
            int status = sendOnce("POST", "/api/auth/login", "127.0.0.1",
                    "203.0.113." + i + ", 198.51.100.10", "198.51.100.10");
            assertThat(status).isEqualTo(200);
        }

        int status = sendOnce("POST", "/api/auth/login", "127.0.0.1",
                "203.0.113.99, 198.51.100.10", "198.51.100.10");
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("X-Real-IP가 없으면 로컬 프록시가 마지막에 추가한 X-Forwarded-For를 사용")
    void localProxy_lastForwardedIpUsed() throws Exception {
        sendTimes("POST", "/api/auth/login", "127.0.0.1", "1.1.1.1, 2.2.2.2", 5);

        int status429 = sendOnce("POST", "/api/auth/login", "127.0.0.1", "9.9.9.9, 2.2.2.2");
        assertThat(status429).isEqualTo(429);

        int status200 = sendOnce("POST", "/api/auth/login", "127.0.0.1", "1.1.1.1, 3.3.3.3");
        assertThat(status200).isEqualTo(200);
    }

    @Test
    @DisplayName("직접 연결 요청은 전달 헤더를 신뢰하지 않고 RemoteAddr로 카운트")
    void directRequest_forwardedHeadersIgnored() throws Exception {
        for (int i = 0; i < 5; i++) {
            int status = sendOnce("POST", "/api/auth/login", "198.51.100.20",
                    "203.0.113." + i, "192.0.2." + i);
            assertThat(status).isEqualTo(200);
        }

        int status = sendOnce("POST", "/api/auth/login", "198.51.100.20",
                "203.0.113.99", "192.0.2.99");
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("X-Forwarded-For 없을 때 RemoteAddr 사용")
    void noXForwardedFor_remoteAddrUsed() throws Exception {
        // X-Forwarded-For 헤더 없음 → remoteAddr(5.5.5.5) 기준으로 5회 채움
        sendTimes("POST", "/api/auth/login", "5.5.5.5", 5);
        // 6번째
        int status = sendOnce("POST", "/api/auth/login", "5.5.5.5", null);
        assertThat(status).isEqualTo(429);
    }

    // ── 5. 보호 대상 아닌 경로는 무제한 통과 ───────────────────────────────────

    @Test
    @DisplayName("보호 대상 아닌 GET /api/events — 제한 없이 통과")
    void nonProtectedPath_alwaysPass() throws Exception {
        int status = sendTimes("GET", "/api/events", "6.6.6.6", 50);
        assertThat(status).isEqualTo(200);
    }

    @Test
    @DisplayName("GET /api/auth/login — 메서드가 POST 아니면 보호 안 함")
    void getMethodOnProtectedPath_pass() throws Exception {
        int status = sendTimes("GET", "/api/auth/login", "7.7.7.7", 50);
        assertThat(status).isEqualTo(200);
    }

    // ── 6. enabled=false 이면 완전 무시 ──────────────────────────────────────

    @Test
    @DisplayName("enabled=false 이면 제한 없이 모두 통과")
    void disabled_alwaysPass() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);
        int status = sendTimes("POST", "/api/auth/login", "8.8.8.8", 100);
        assertThat(status).isEqualTo(200);
    }

    // ── 7. 429 응답 바디 JSON 형식 확인 ─────────────────────────────────────

    @Test
    @DisplayName("429 응답 바디에 success=false, code=R001 포함")
    void exceeded_responseBody_hasErrorCode() throws Exception {
        sendTimes("POST", "/api/auth/login", "9.0.0.1", 5);
        MockHttpServletResponse resp = sendOnceResponse("POST", "/api/auth/login", "9.0.0.1", null);

        assertThat(resp.getStatus()).isEqualTo(429);
        String body = resp.getContentAsString();
        assertThat(body).contains("\"success\"").contains("false");
        assertThat(body).contains("R001");
    }

    // ── 8. Ant 패턴 경로 매칭 ────────────────────────────────────────────────

    @Test
    @DisplayName("oauth/* 패턴 — /api/auth/oauth/kakao 도 제한 적용")
    void oauthWildcard_matched() throws Exception {
        sendTimes("POST", "/api/auth/oauth/kakao", "9.0.0.2", 10);
        int status = sendOnce("POST", "/api/auth/oauth/kakao", "9.0.0.2", null);
        assertThat(status).isEqualTo(429);
    }

    @Test
    @DisplayName("groups/*/invite 패턴 — /api/groups/123/invite 도 제한 적용")
    void groupsInviteWildcard_matched() throws Exception {
        sendTimes("POST", "/api/groups/123/invite", "9.0.0.3", 10);
        int status = sendOnce("POST", "/api/groups/123/invite", "9.0.0.3", null);
        assertThat(status).isEqualTo(429);
    }
}

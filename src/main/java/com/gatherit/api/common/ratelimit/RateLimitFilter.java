package com.gatherit.api.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.exception.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Caffeine 인메모리 카운터로 IP별 요청 빈도를 제한합니다.
 *
 * 보호 대상:
 *   - POST /api/auth/login    : 60s 동안 5회
 *   - POST /api/auth/signup   : 600s 동안 3회
 *   - POST /api/auth/refresh  : 60s 동안 10회
 *   - POST /api/groups/{*}/invite : 60s 동안 10회
 *   - POST /api/groups/join   : 60s 동안 20회   ← 초대 토큰 brute force 차단
 *   - POST /api/auth/oauth/*  : 60s 동안 10회   ← OAuth 콜백 abuse 차단
 *   - POST /api/me/calendar/import : 60s 동안 5회   ← .ics import 남용 차단
 *   - GET  /api/public/polls/{token}      : 60s 동안 60회 ← 공개 투표 상세 스크래핑/도배 차단(읽기)
 *   - POST /api/public/polls/{token}/vote : 60s 동안 30회 ← 공개 투표 도배 차단
 *
 * NOTE: 단일 인스턴스 전제. 분산 전환 시 Redis 어댑터로 교체.
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper om = new ObjectMapper();
    private final PathMatcher pathMatcher = new AntPathMatcher();

    /** key="{method}:{pattern}:{ip}" → 누적 카운터+만료시각 */
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(15))   // 가장 긴 룰 TTL 보다 여유 있게
            .maximumSize(100_000)
            .build();

    @Value("${gatherit.rate-limit.enabled:true}")
    private boolean enabled;

    private static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/auth/login",      5,  Duration.ofSeconds(60)),
            new Rule("POST", "/api/auth/signup",     3,  Duration.ofSeconds(600)),
            new Rule("POST", "/api/auth/refresh",    10, Duration.ofSeconds(60)),
            new Rule("POST", "/api/auth/oauth/*",    10, Duration.ofSeconds(60)),
            new Rule("POST", "/api/groups/*/invite", 10, Duration.ofSeconds(60)),
            new Rule("POST", "/api/groups/join",     20, Duration.ofSeconds(60)),
            new Rule("GET",  "/api/public/polls/*", 60, Duration.ofSeconds(60)),  // 공개 투표 상세 조회 — 스크래핑/도배 방어(읽기라 넉넉히). 하위 /vote와 /handoff는 세그먼트가 달라 안 걸림
            new Rule("POST", "/api/public/polls/*/vote", 30, Duration.ofSeconds(60)),
            new Rule("POST", "/api/public/polls/*/handoff", 20, Duration.ofSeconds(60)),
            new Rule("POST", "/api/polls/linked/claim-code", 10, Duration.ofSeconds(60)),
            new Rule("POST", "/api/polls/linked/*/claim", 20, Duration.ofSeconds(60)),
            new Rule("POST", "/api/polls/linked/*/join-group", 10, Duration.ofSeconds(60)),
            new Rule("POST", "/api/me/calendar/import", 5, Duration.ofSeconds(60)),  // .ics import 남용 차단
            new Rule("POST", "/api/ai/schedule/extract", 5, Duration.ofSeconds(60)),  // AI 추출 burst/재시도 폭주 차단(일 쿼터와 별개)
            new Rule("POST", "/api/ai/schedule/commit", 10, Duration.ofSeconds(60))  // AI 등록 대량/연타 차단
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        Rule matched = matchRule(request);
        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        String key = "%s:%s:%s".formatted(matched.method, matched.pattern, ip);
        long now = System.currentTimeMillis();
        long ttlMs = matched.ttl.toMillis();

        Window w = windows.get(key, k -> new Window(now + ttlMs));
        boolean exceeded;
        synchronized (w) {
            if (now > w.expiresAt) {
                // 윈도우 지남 → 리셋
                w.count = 1;
                w.expiresAt = now + ttlMs;
                exceeded = false;
            } else {
                w.count++;
                exceeded = w.count > matched.limit;
            }
        }

        if (exceeded) {
            log.warn("Rate limit hit ip={} {} {}", ip, matched.method, request.getRequestURI());
            writeError(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private Rule matchRule(HttpServletRequest req) {
        if (!StringUtils.hasText(req.getRequestURI())) return null;
        for (Rule r : RULES) {
            if (r.method.equalsIgnoreCase(req.getMethod()) && pathMatcher.match(r.pattern, req.getRequestURI())) {
                return r;
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest req) {
        String remoteAddr = req.getRemoteAddr();
        if (!isLocalReverseProxy(remoteAddr)) {
            return remoteAddr;
        }

        // 운영 Nginx가 외부 입력을 덮어써서 전달하는 값이다.
        String realIp = req.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) return realIp.trim();

        // X-Real-IP가 없는 로컬 프록시도 지원한다. proxy_add_x_forwarded_for가
        // 직접 연결한 클라이언트 IP를 끝에 추가하므로 조작 가능한 첫 값이 아닌 마지막 값을 쓴다.
        String fwd = req.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(fwd)) {
            String[] forwarded = fwd.split(",");
            return forwarded[forwarded.length - 1].trim();
        }
        return remoteAddr;
    }

    private boolean isLocalReverseProxy(String remoteAddr) {
        return "127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }

    private void writeError(HttpServletResponse response) throws IOException {
        ErrorCode ec = ErrorCode.RATE_LIMIT_EXCEEDED;
        response.setStatus(ec.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(om.writeValueAsString(
                Map.of("success", false, "code", ec.getCode(), "message", ec.getMessage())
        ));
    }

    private record Rule(String method, String pattern, int limit, Duration ttl) {}

    /** mutable — synchronized 블록 안에서만 수정 */
    private static class Window {
        int count;
        long expiresAt;

        Window(long expiresAt) {
            // count=0 으로 시작 — 첫 요청에서 doFilterInternal 의 count++ 로 1 이 된다.
            // (1 로 시작하면 첫 요청이 2 로 집계돼 limit 보다 1 적게 허용되는 off-by-one)
            this.count = 0;
            this.expiresAt = expiresAt;
        }
    }
}

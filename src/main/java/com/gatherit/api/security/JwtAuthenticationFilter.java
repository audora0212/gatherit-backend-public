package com.gatherit.api.security;

import com.gatherit.api.exception.CustomException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authorization: Bearer <token> 헤더에서 JWT 추출 → 인증 컨텍스트 세팅.
 * 토큰 없거나 무효면 SecurityContext 비워둔 채 통과 (이후 SecurityConfig 의 authorize 룰이 401/403 반환).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (StringUtils.hasText(token) && jwtUtils.validateAccessToken(token)) {
                Long userId = jwtUtils.getUserId(token);
                UserDetailsImpl userDetails = userDetailsService.loadUserById(userId);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (CustomException e) {
            // 토큰 무효 — context 안 채움. 컨트롤러 진입 시 SecurityConfig 가 401 처리.
            log.debug("JWT 인증 실패: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HEADER);
        if (StringUtils.hasText(bearer) && bearer.startsWith(PREFIX)) {
            return bearer.substring(PREFIX.length());
        }
        return null;
    }
}

package com.gatherit.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 비인증 요청 → 401 반환.
 * Spring Security 기본 AuthenticationEntryPoint 는 403 을 반환하지만, FE 의 토큰 회전 로직이
 * 401 을 트리거로 동작하므로 (REST 컨벤션 + axios interceptor) 401 로 일치시킴.
 */
@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Unauthenticated request to {} → 401: {}", request.getRequestURI(), authException.getMessage());

        ApiResponse<Void> body = ApiResponse.error(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

package com.gatherit.api.exception;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.common.notify.SlackNotifier;
import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 단위 테스트.
 * SlackNotifier 만 Mock — 나머지는 실제 객체 직접 테스트.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    SlackNotifier slack;

    GlobalExceptionHandler handler;

    @Test
    void excessMultipartParts_is400WithoutLeakingParserDetails() {
        var response = new GlobalExceptionHandler(slack)
                .handleInvalidMultipart(new MultipartException("private parser details",
                        new FileCountLimitExceededException("files", 32)), new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("C001");
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INVALID_INPUT.getMessage());
        org.mockito.Mockito.verifyNoInteractions(slack);
    }

    @Test
    void spring7SizeWrapperForExcessParts_keeps400WithoutAlert() {
        var failure = new MaxUploadSizeExceededException(-1,
                new IllegalStateException("private parser details",
                        new FileCountLimitExceededException("files", 32)));
        var response = new GlobalExceptionHandler(slack).handleMultipartTooLarge(failure);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getCode()).isEqualTo("C001");
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.INVALID_INPUT.getMessage());
        org.mockito.Mockito.verifyNoInteractions(slack);
    }

    @Test
    void unexpectedMultipartStorageFailure_remains500AndAlerts() {
        var failure = new MultipartException("storage unavailable", new java.io.IOException("disk failure"));
        var request = new MockHttpServletRequest("POST", "/api/groups/1/photos");
        var response = new GlobalExceptionHandler(slack).handleInvalidMultipart(failure, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getCode()).isEqualTo("C999");
        org.mockito.Mockito.verify(slack).error("500 Error POST /api/groups/1/photos", failure);
    }

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(slack);
    }

    // ── 1. CustomException → 상태/바디 매핑 ────────────────────────────────

    @Test
    @DisplayName("CustomException(NOT_FOUND) → 404 + C004 코드")
    void handleCustom_notFound() {
        CustomException ex = new CustomException(ErrorCode.NOT_FOUND);
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustom(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getCode()).isEqualTo("C004");
        assertThat(res.getBody().getMessage()).isEqualTo(ErrorCode.NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("CustomException(UNAUTHORIZED) → 401 + C002 코드")
    void handleCustom_unauthorized() {
        CustomException ex = new CustomException(ErrorCode.UNAUTHORIZED);
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustom(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getCode()).isEqualTo("C002");
    }

    @Test
    @DisplayName("CustomException(detail 메시지) → body.message 는 detail 값")
    void handleCustom_withDetail() {
        CustomException ex = new CustomException(ErrorCode.LOGIN_FAILED, "커스텀 상세 메시지");
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustom(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getMessage()).isEqualTo("커스텀 상세 메시지");
    }

    @Test
    @DisplayName("CustomException(RATE_LIMIT_EXCEEDED) → 429 + R001 코드")
    void handleCustom_rateLimitExceeded() {
        CustomException ex = new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustom(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res.getBody().getCode()).isEqualTo("R001");
    }

    @Test
    @DisplayName("CustomException(FORBIDDEN) → 403 + C003 코드")
    void handleCustom_forbidden() {
        CustomException ex = new CustomException(ErrorCode.FORBIDDEN);
        ResponseEntity<ApiResponse<Void>> res = handler.handleCustom(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getCode()).isEqualTo("C003");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 2. Validation 예외 (MethodArgumentNotValidException) ─────────────────

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 + C001 + 필드 에러 메시지 포함")
    void handleValidation_fieldErrors() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fe1 = new FieldError("req", "title", "제목은 필수입니다");
        FieldError fe2 = new FieldError("req", "startAt", "시작 시각은 필수입니다");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fe1, fe2));

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiResponse<Void>> res = handler.handleValidation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("C001");
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getMessage())
                .contains("title")
                .contains("제목은 필수입니다")
                .contains("startAt")
                .contains("시작 시각은 필수입니다");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 필드 에러 포맷: 'field: message'")
    void handleValidation_messageFormat() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fe = new FieldError("req", "email", "올바른 이메일 형식이 아닙니다");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fe));

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ApiResponse<Void>> res = handler.handleValidation(ex);

        assertThat(res.getBody().getMessage()).isEqualTo("email: 올바른 이메일 형식이 아닙니다");
    }

    // ── 3. AuthenticationException ──────────────────────────────────────────

    @Test
    @DisplayName("AuthenticationException → 401 + C002")
    void handleAuth_401() {
        AuthenticationException ex = mock(AuthenticationException.class);
        ResponseEntity<ApiResponse<Void>> res = handler.handleAuth(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getCode()).isEqualTo("C002");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 4. AccessDeniedException ─────────────────────────────────────────────

    @Test
    @DisplayName("AccessDeniedException → 403 + C003")
    void handleAccessDenied_403() {
        AccessDeniedException ex = new AccessDeniedException("access denied");
        ResponseEntity<ApiResponse<Void>> res = handler.handleAccessDenied(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getCode()).isEqualTo("C003");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 5. MethodArgumentTypeMismatchException ────────────────────────────────

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400 + C001 + 파라미터명/값 메시지")
    void handleTypeMismatch_400() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("eventId");
        when(ex.getValue()).thenReturn("NaN");

        ResponseEntity<ApiResponse<Void>> res = handler.handleTypeMismatch(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("C001");
        assertThat(res.getBody().getMessage())
                .contains("eventId")
                .contains("NaN");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 6. MaxUploadSizeExceededException ────────────────────────────────────

    @Test
    @DisplayName("MaxUploadSizeExceededException → 413 + PH005")
    void handleMultipartTooLarge_413() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10_485_760L);
        ResponseEntity<ApiResponse<Void>> res = handler.handleMultipartTooLarge(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody().getCode()).isEqualTo("PH005");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 7. 일반 Exception (handleUnknown) ────────────────────────────────────

    @Test
    @DisplayName("일반 Exception → 500 + C999 + Slack 알림 호출")
    void handleUnknown_500() {
        RuntimeException ex = new RuntimeException("뜻밖의 오류");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events");

        ResponseEntity<ApiResponse<Void>> res = handler.handleUnknown(ex, req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getCode()).isEqualTo("C999");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("handleUnknown — success 필드는 항상 false")
    void handleUnknown_successFalse() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/groups/1/invite");
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleUnknown(new IllegalStateException("state error"), req);

        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 7b. NoResourceFoundException (없는 경로) → 404, 500 아님 ──────────────

    @Test
    @DisplayName("NoResourceFoundException(없는 경로) → 404 + C004 (500 아님)")
    void handleNoResource_404() {
        NoResourceFoundException ex =
                new NoResourceFoundException(HttpMethod.POST, "api/groups/163/join-request",
                        "No static resource api/groups/163/join-request.");

        ResponseEntity<ApiResponse<Void>> res = handler.handleNoResource(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getCode()).isEqualTo("C004");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 7c. HttpMessageNotReadableException (깨진 본문/인코딩) → 400, 500 아님 ──

    @Test
    @DisplayName("HttpMessageNotReadableException(깨진 본문) → 400 + C001 (500 아님)")
    void handleNotReadable_400() {
        HttpInputMessage input = mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new RuntimeException("Invalid UTF-8 middle byte 0xd7"), input);

        ResponseEntity<ApiResponse<Void>> res = handler.handleNotReadable(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("C001");
        assertThat(res.getBody().isSuccess()).isFalse();
    }

    // ── 8. 다양한 도메인 에러코드 매핑 검증 ──────────────────────────────────

    @Test
    @DisplayName("CustomException(GROUP_NOT_FOUND) → 404 + G001")
    void handleCustom_groupNotFound() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleCustom(new CustomException(ErrorCode.GROUP_NOT_FOUND));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getCode()).isEqualTo("G001");
    }

    @Test
    @DisplayName("CustomException(EXPIRED_TOKEN) → 401 + A004")
    void handleCustom_expiredToken() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleCustom(new CustomException(ErrorCode.EXPIRED_TOKEN));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getCode()).isEqualTo("A004");
    }

    @Test
    @DisplayName("CustomException(POLL_DEADLINE_PASSED) → 400 + P003")
    void handleCustom_pollDeadlinePassed() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleCustom(new CustomException(ErrorCode.POLL_DEADLINE_PASSED));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("P003");
    }
}

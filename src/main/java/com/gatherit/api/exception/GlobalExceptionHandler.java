package com.gatherit.api.exception;

import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.common.notify.SlackNotifier;
import org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final SlackNotifier slack;

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustom(CustomException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("CustomException [{}] {}", ec.getCode(), e.getMessage());
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining(", "));
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), message));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException e) {
        ErrorCode ec = ErrorCode.UNAUTHORIZED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        ErrorCode ec = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /**
     * @PathVariable / @RequestParam 타입 변환 실패 — 예: /api/events/NaN 같이 Long 파라미터에 invalid 값.
     * 기본은 unhandled → 500. 의도된 동작은 400. (FE 의 잘못된 deep link / 깨진 캐시 케이스).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "잘못된 요청 — " + e.getName() + "='" + e.getValue() + "'";
        log.warn("MethodArgumentTypeMismatch: {}", msg);
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), msg));
    }

    /**
     * 존재하지 않는 경로 — 매핑되는 핸들러/정적 리소스가 없으면 Spring 이 던짐.
     * 기본은 unhandled → 500(C999)로 집계돼 5xx 모니터링을 오염시킴. 의도된 동작은 404.
     * (오타 난 API 호출 / 구버전/잘못된 앱 / 외부 스캐너 — 서버 장애 아님.)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("NoResourceFound: {}", e.getMessage());
        ErrorCode ec = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /**
     * 요청 본문 파싱 실패 — 깨진 JSON / 잘못된 인코딩(non-UTF-8 바이트) 등.
     * 기본은 unhandled → 500. 의도된 동작은 400. (클라 직렬화 버그 / 손상된 전송.)
     * 파싱 내부 메시지는 로그로만, 클라엔 일반 문구.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadable: {}", e.getMostSpecificCause().getMessage());
        ErrorCode ec = ErrorCode.INVALID_INPUT;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /** Spring multipart 가 service 도착 전에 던지는 size 초과 — PHOTO_TOO_LARGE 로 매핑. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartTooLarge(MaxUploadSizeExceededException e) {
        // Spring 7 also wraps Tomcat's part-count bound in this size exception.
        // Preserve the existing 400 contract for too many parts, not 413 bytes.
        ErrorCode ec = e.getMostSpecificCause() instanceof FileCountLimitExceededException
                ? ErrorCode.INVALID_INPUT : ErrorCode.PHOTO_TOO_LARGE;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /** A parser part-count rejection is client input; storage/parser faults must still alert. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidMultipart(MultipartException e, HttpServletRequest req) {
        if (e.getMostSpecificCause() instanceof FileCountLimitExceededException) {
            ErrorCode ec = ErrorCode.INVALID_INPUT;
            return ResponseEntity.status(ec.getStatus())
                    .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
        }
        return handleUnknown(e, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception {} {}", req.getMethod(), req.getRequestURI(), e);
        slack.error("500 Error %s %s".formatted(req.getMethod(), req.getRequestURI()), e);
        ErrorCode ec = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}

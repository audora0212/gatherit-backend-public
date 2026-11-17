package com.gatherit.api.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러 코드. 새 도메인 추가 시 prefix 로 그루핑.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ─── COMMON 0xx ───
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "입력값이 올바르지 않습니다"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "C002", "인증이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "C003", "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "리소스를 찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "서버 오류"),

    // ─── AUTH 1xx ───
    EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "A001", "이미 사용 중인 이메일입니다"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A002", "아이디 또는 비밀번호가 올바르지 않습니다"),
    USERNAME_ALREADY_USED(HttpStatus.CONFLICT, "A007", "이미 사용 중인 아이디입니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A003", "유효하지 않은 토큰입니다"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "A004", "만료된 토큰입니다"),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "A005", "카카오 인증에 실패했습니다"),
    APPLE_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "A008", "Apple 인증에 실패했습니다"),
    GOOGLE_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "A009", "Google 인증에 실패했습니다"),

    // ─── USER 2xx ───
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U001", "사용자를 찾을 수 없습니다"),

    // ─── GROUP 3xx ───
    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "G001", "그룹을 찾을 수 없습니다"),
    NOT_GROUP_MEMBER(HttpStatus.FORBIDDEN, "G002", "해당 그룹의 멤버가 아닙니다"),
    NOT_GROUP_OWNER(HttpStatus.FORBIDDEN, "G003", "그룹장만 가능합니다"),
    // (소유자 라벨 통일 — 위 G003 동일)
    ALREADY_GROUP_MEMBER(HttpStatus.CONFLICT, "G004", "이미 그룹 멤버입니다"),
    INVITE_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "G005", "초대 토큰이 유효하지 않습니다"),
    INVITE_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "G006", "초대 토큰이 만료되었습니다"),
    OWNER_CANNOT_LEAVE(HttpStatus.BAD_REQUEST, "G007", "그룹장은 그룹을 떠날 수 없습니다. 삭제 또는 그룹장 위임 후 가능"),
    PERSONAL_GROUP_NOT_INVITABLE(HttpStatus.BAD_REQUEST, "G008", "개인 캘린더는 초대할 수 없습니다"),
    PERSONAL_GROUP_NO_POLL(HttpStatus.BAD_REQUEST, "G009", "개인 캘린더는 투표를 만들 수 없습니다"),
    GROUP_INVITE_FORBIDDEN(HttpStatus.FORBIDDEN, "G010", "초대 권한이 없어요. 그룹장만 초대할 수 있어요"),
    GROUP_EVENT_CREATE_FORBIDDEN(HttpStatus.FORBIDDEN, "G011", "일정을 만들 권한이 없어요. 그룹장만 가능해요"),
    GROUP_POLL_CREATE_FORBIDDEN(HttpStatus.FORBIDDEN, "G012", "투표를 만들 권한이 없어요. 그룹장만 가능해요"),
    GROUP_PUBLIC_POLL_JOIN_FORBIDDEN(HttpStatus.FORBIDDEN, "G013", "이 그룹은 초대 전용입니다. 그룹장에게 초대 링크를 받아주세요"),

    // ─── EVENT 4xx ───
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "E001", "일정을 찾을 수 없습니다"),
    EVENT_FORBIDDEN(HttpStatus.FORBIDDEN, "E002", "해당 일정에 접근 권한이 없습니다"),
    EVENT_TIME_INVALID(HttpStatus.BAD_REQUEST, "E003", "종료 시각이 시작 시각보다 빠릅니다"),
    EVENT_NO_GROUPS(HttpStatus.BAD_REQUEST, "E004", "공유할 그룹을 1개 이상 선택해야 합니다"),
    EVENT_SHADOW_NESTED(HttpStatus.BAD_REQUEST, "E005", "그림자 일정 위에 또 그림자를 만들 수 없습니다"),
    EVENT_GROUPS_LOCKED(HttpStatus.BAD_REQUEST, "E006", "원본 일정의 공유 그룹은 변경할 수 없습니다 — 다른 그룹에 알리려면 그림자를 만드세요"),

    // ─── POLL 5xx ───
    POLL_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "투표를 찾을 수 없습니다"),
    POLL_FORBIDDEN(HttpStatus.FORBIDDEN, "P002", "해당 투표에 접근 권한이 없습니다"),
    POLL_DEADLINE_PASSED(HttpStatus.BAD_REQUEST, "P003", "투표 마감 시각이 지났습니다"),
    POLL_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "P004", "이미 확정된 투표입니다"),
    POLL_CONFIRM_RACE(HttpStatus.CONFLICT, "P005", "동시 확정 시도 — 다시 시도해주세요"),
    POLL_NOT_PROPOSER(HttpStatus.FORBIDDEN, "P006", "투표 발의자만 확정 가능합니다"),
    POLL_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "P007", "선택한 슬롯을 찾을 수 없습니다"),
    POLL_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "P008", "확정되지 않은 투표입니다"),
    POLL_EVENT_ALREADY_LINKED(HttpStatus.CONFLICT, "P009", "이미 다른 사용자가 일정을 추가했습니다"),
    POLL_SHARE_INVALID(HttpStatus.NOT_FOUND, "P010", "투표 링크가 만료됐거나 유효하지 않습니다"),
    POLL_GUEST_NAME_TAKEN(HttpStatus.CONFLICT, "P011", "이미 사용 중인 닉네임입니다"),
    POLL_GUEST_SESSION_INVALID(HttpStatus.FORBIDDEN, "P012", "이 브라우저의 참여 정보를 확인할 수 없습니다"),
    POLL_HANDOFF_INVALID(HttpStatus.FORBIDDEN, "P013", "앱 연결 코드가 만료되었거나 유효하지 않습니다"),
    POLL_GUEST_ALREADY_LINKED(HttpStatus.CONFLICT, "P014", "이 투표 응답은 이미 다른 계정에 연결되어 있습니다"),
    POLL_LINKED_ACCESS_DENIED(HttpStatus.FORBIDDEN, "P015", "이 계정에 연결된 공개 투표가 아닙니다"),

    // ─── PHOTO 6xx ───
    PHOTO_NOT_FOUND(HttpStatus.NOT_FOUND, "PH001", "사진을 찾을 수 없습니다"),
    PHOTO_FORBIDDEN(HttpStatus.FORBIDDEN, "PH002", "사진 권한이 없습니다"),
    PHOTO_STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "PH003", "사진 저장 중 오류"),
    PHOTO_QUOTA_EXCEEDED(HttpStatus.CONFLICT, "PH004", "그룹당 사진 100장을 초과할 수 없어요"),
    PHOTO_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "PH005", "사진은 10MB 이하만 업로드 가능해요"),
    PHOTO_BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "PH006", "한 번에 처리할 수 있는 사진 수를 초과했어요"),
    PHOTO_INVALID_TYPE(HttpStatus.BAD_REQUEST, "PH007", "지원하지 않는 이미지 형식입니다"),

    // ─── NOTIFICATION 7xx ───
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N001", "알림을 찾을 수 없습니다"),

    // ─── RATE LIMIT ───
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "R001", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"),

    // ─── AI (일정 가져오기) ───
    AI_PROVIDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AI001", "AI 일정 기능이 아직 설정되지 않았어요"),
    AI_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AI002", "오늘 AI 일정 가져오기 한도를 모두 사용했어요"),
    AI_EXTRACTION_FAILED(HttpStatus.BAD_GATEWAY, "AI003", "일정 추출에 실패했어요. 잠시 후 다시 시도해주세요"),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;
}

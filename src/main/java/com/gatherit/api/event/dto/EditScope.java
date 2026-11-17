package com.gatherit.api.event.dto;

/**
 * 반복 일정 수정/삭제 적용 범위.
 * - THIS: 이 회차만 (EXDATE 추가 + 수정 시 오버라이드 생성)
 * - THIS_AND_FUTURE: 이 회차 이후 전체 (시리즈를 UNTIL 로 절단 + 이후 새 시리즈)
 * - ALL: 시리즈 전체 (기존 동작). 미지정 시 기본값.
 */
public enum EditScope {
    THIS,
    THIS_AND_FUTURE,
    ALL
}

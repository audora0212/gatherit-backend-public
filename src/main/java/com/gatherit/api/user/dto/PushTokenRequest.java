package com.gatherit.api.user.dto;

/**
 * 앱이 expo-notifications 의 getExpoPushTokenAsync 결과를 등록.
 * null 또는 빈 문자열이면 토큰 제거 (사용자가 권한 회수).
 */
public record PushTokenRequest(
        String expoPushToken
) {}

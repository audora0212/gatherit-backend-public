package com.gatherit.api.auth.dto;

/**
 * Apple identityToken JWT payload 에서 추출한 사용자 식별 정보.
 * sub = Apple user ID (계정마다 고유, provider 별 식별자로 사용).
 * email 은 처음 동의 시에만 들어옴 — Apple 의 private relay 주소일 수 있음.
 */
public record AppleUserInfo(
        String sub,
        String email,
        Boolean emailVerified
) {}

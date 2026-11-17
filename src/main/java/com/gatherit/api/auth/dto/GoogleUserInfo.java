package com.gatherit.api.auth.dto;

/**
 * Google idToken JWT payload 에서 추출한 사용자 정보.
 * sub = Google user ID. email/name/picture 는 사용자가 동의한 경우 포함.
 */
public record GoogleUserInfo(
        String sub,
        String email,
        Boolean emailVerified,
        String name,
        String picture
) {}

package com.gatherit.api.ical;

/** iCal 구독 토큰 응답 — webcal URL 은 FE 가 token + groupId 로 조립. */
public record IcalTokenResponse(String token) {
}

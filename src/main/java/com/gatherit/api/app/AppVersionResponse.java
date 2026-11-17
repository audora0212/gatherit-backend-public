package com.gatherit.api.app;

/**
 * 앱 버전 안내 응답 — 플랫폼별 최신/최소 지원 버전 + 스토어 URL.
 * 앱이 콜드스타트에서 자기 버전과 비교해 업데이트 안내(소프트/강제) 결정.
 */
public record AppVersionResponse(Platform ios, Platform android) {

    public record Platform(String latest, String min, String storeUrl) {}
}

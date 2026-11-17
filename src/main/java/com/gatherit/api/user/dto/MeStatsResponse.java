package com.gatherit.api.user.dto;

/**
 * MY 탭 대시보드 stats — 무거운 list endpoint 들을 한 번에 카운트로 대체.
 * groupCount: 사용자가 속한 그룹 수 (개인 그룹 "내 캘린더" 제외)
 * sharedPhotoCount: 비-개인 그룹들의 사진 합계
 * upcomingEventCount: startAt > now 인 이벤트 수 (사용자가 볼 수 있는 모든 그룹 기준)
 */
public record MeStatsResponse(
        long groupCount,
        long sharedPhotoCount,
        long upcomingEventCount
) {}

package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.GroupColor;

/**
 * 초대 토큰으로 가입 전 그룹 정보 미리보기.
 * FE 가 참여 확인 모달에서 그룹 이름/멤버수/이미 가입 여부 표시용으로 사용.
 */
public record GroupPreviewResponse(
        Long id,
        String name,
        GroupColor colorKey,
        long memberCount,
        boolean alreadyMember
) {}

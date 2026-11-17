package com.gatherit.api.group.entity;

/**
 * 그룹 단위 일반 권한 정책 — invite/event-create/poll-create 등에서 공용으로 사용.
 *  - ANYONE: 그룹 멤버 누구나 가능 (default)
 *  - OWNER_ONLY: 소유자만 가능
 */
public enum GroupMemberPolicy {
    ANYONE,
    OWNER_ONLY,
}

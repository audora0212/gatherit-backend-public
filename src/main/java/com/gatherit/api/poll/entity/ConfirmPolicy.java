package com.gatherit.api.poll.entity;

public enum ConfirmPolicy {
    /** 발의자만 확정 가능 */
    PROPOSER_ONLY,
    /** 그룹 멤버 누구나 확정 가능 */
    ANYONE,
}

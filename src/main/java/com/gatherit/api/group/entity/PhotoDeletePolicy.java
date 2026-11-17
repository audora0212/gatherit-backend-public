package com.gatherit.api.group.entity;

/**
 * 그룹 사진 삭제 권한 정책.
 *  - ANYONE: 그룹 멤버 누구나 삭제 가능 (default)
 *  - UPLOADER_ONLY: 사진 올린 본인만 삭제 가능
 */
public enum PhotoDeletePolicy {
    ANYONE,
    UPLOADER_ONLY,
}

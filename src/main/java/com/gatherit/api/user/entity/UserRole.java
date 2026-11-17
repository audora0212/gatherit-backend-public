package com.gatherit.api.user.entity;

/** 계정 응답과 기존 DB 행의 역할 값 호환. 이 저장소에서는 관리자 권한을 부여하지 않는다. */
public enum UserRole {
    USER,
    SUB_ADMIN,
    ADMIN,
}

package com.gatherit.api.announcement;

/** 공지 종류 — FE 가 라벨/색으로 구분. 점검은 별도 UI 없이 이 type 으로만 표기. */
public enum AnnouncementType {
    NOTICE,       // 일반 공지
    UPDATE,       // 업데이트/신기능 안내
    MAINTENANCE   // 서버 점검
}

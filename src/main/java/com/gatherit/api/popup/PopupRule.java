package com.gatherit.api.popup;

/** 팝업 재노출 규칙 — ONCE: 유저당 1회(닫으면 끝) / DAILY: 하루 1회(KST 날짜 기준, 기간 동안 매일). */
public enum PopupRule {
    ONCE,
    DAILY,
}

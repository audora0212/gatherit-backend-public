package com.gatherit.api.ical;

/** 외부 .ics import 요청 — 파일 내용을 통째로 전달. */
public record IcalImportRequest(String ics) {
}

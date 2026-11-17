package com.gatherit.api.announcement;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository repository;

    /** 진입 모달용 — 현재 노출 기간인 공지. */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> getActive() {
        return repository.findActive(Instant.now()).stream()
                .map(AnnouncementResponse::from)
                .toList();
    }

    /** MY 공지 리스트용 — 최근 공지 (만료 포함). */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> list() {
        return repository.findTop50ByOrderByStartsAtDesc().stream()
                .map(AnnouncementResponse::from)
                .toList();
    }
}

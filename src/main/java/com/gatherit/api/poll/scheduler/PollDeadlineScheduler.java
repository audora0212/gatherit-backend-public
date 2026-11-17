package com.gatherit.api.poll.scheduler;

import com.gatherit.api.poll.service.PollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매 5분마다 dead poll cleanup — 마지막 슬롯 날짜 지났고 미확정인 폴 삭제.
 * 자동 확정은 하지 않음 (deadline 지나도 권한자가 수동 확정).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PollDeadlineScheduler {

    private final PollService pollService;

    @Scheduled(cron = "0 */5 * * * *")
    public void cleanupDeadPolls() {
        log.debug("[Scheduler] poll deadline notify + cleanup");
        // 마감 도달 폴 발의자 알림 먼저 — 그 다음 죽은 폴 삭제 (삭제 전에 알림 기회 보장).
        pollService.notifyDeadlineReachedPolls();
        pollService.cleanupDeadPolls();
    }
}

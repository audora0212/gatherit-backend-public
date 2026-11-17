package com.gatherit.api.photo.scheduler;

import com.gatherit.api.photo.service.PhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 3시 — 만료 사진 일괄 삭제 (배치).
 * batch size 만큼만 한 트랜잭션으로 처리, 남은 건 다음 cron 으로.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoExpiryScheduler {

    private final PhotoService photoService;

    @Value("${gatherit.photo.expiry-batch-size:200}")
    private int batchSize;

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupExpired() {
        int total = 0;
        int round = 0;
        while (true) {
            int n = photoService.deleteExpiredBatch(batchSize);
            total += n;
            round++;
            if (n < batchSize) break; // 배치 미만 = 마지막 라운드
            if (round > 100) {         // 안전 장치 — 무한루프 방지
                log.warn("Photo cleanup safety stop after {} rounds, total={}", round, total);
                break;
            }
        }
        log.info("Photo expiry cleanup done: total={} rows in {} rounds", total, round);
    }
}

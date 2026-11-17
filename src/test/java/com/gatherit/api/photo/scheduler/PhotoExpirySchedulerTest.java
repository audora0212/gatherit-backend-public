package com.gatherit.api.photo.scheduler;

import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.service.PhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * PhotoExpiryScheduler 단위 테스트.
 *
 * PhotoService.deleteExpiredBatch(int) 의 반환값을 mock 해서
 * 스케줄러의 while 루프/안전장치 로직을 단독 검증.
 *
 * 검증 포인트:
 *  1. 만료 사진 0건 — 1라운드 후 종료
 *  2. batchSize 미만 반환 — 1라운드 후 종료
 *  3. 정확히 batchSize — 2라운드(마지막 0건)에서 종료
 *  4. 연속 full-batch — 100라운드 초과 시 안전장치 break
 *  5. deleteExpiredBatch 에 batchSize 가 올바르게 전달되는지
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoExpirySchedulerTest {

    @Mock PhotoService photoService;

    PhotoExpiryScheduler scheduler;

    private static final int BATCH = 5;

    @BeforeEach
    void setUp() {
        scheduler = new PhotoExpiryScheduler(photoService);
        ReflectionTestUtils.setField(scheduler, "batchSize", BATCH);
    }

    // ── 라운드 종료 조건 ─────────────────────────────────

    @Test
    @DisplayName("cleanupExpired — 만료 사진 0건이면 deleteExpiredBatch 1회 후 종료")
    void cleanup_noExpired_singleRound() {
        when(photoService.deleteExpiredBatch(anyInt())).thenReturn(0);

        scheduler.cleanupExpired();

        verify(photoService, times(1)).deleteExpiredBatch(BATCH);
    }

    @Test
    @DisplayName("cleanupExpired — batchSize 미만(3건) 반환 → 1라운드 후 종료")
    void cleanup_lessThanBatch_singleRound() {
        when(photoService.deleteExpiredBatch(BATCH)).thenReturn(3); // 3 < BATCH(5)

        scheduler.cleanupExpired();

        verify(photoService, times(1)).deleteExpiredBatch(BATCH);
    }

    @Test
    @DisplayName("cleanupExpired — 정확히 batchSize(5건) → 2라운드(마지막 0건으로 종료)")
    void cleanup_exactBatch_twoRounds() {
        when(photoService.deleteExpiredBatch(BATCH))
                .thenReturn(BATCH)  // 1라운드: 5건 = batchSize → 계속
                .thenReturn(0);     // 2라운드: 0건 < batchSize → 종료

        scheduler.cleanupExpired();

        verify(photoService, times(2)).deleteExpiredBatch(BATCH);
    }

    @Test
    @DisplayName("cleanupExpired — 3라운드(full→full→2건) 정상 종료")
    void cleanup_threeRounds_normalExit() {
        when(photoService.deleteExpiredBatch(BATCH))
                .thenReturn(BATCH)  // 1라운드
                .thenReturn(BATCH)  // 2라운드
                .thenReturn(2);     // 3라운드: 2 < 5 → 종료

        scheduler.cleanupExpired();

        verify(photoService, times(3)).deleteExpiredBatch(BATCH);
    }

    @Test
    @DisplayName("cleanupExpired — 100라운드 초과 시 안전장치로 루프 탈출")
    void cleanup_safetyStopAfter100Rounds() {
        // 항상 full-batch 반환 → round > 100 에서 break
        when(photoService.deleteExpiredBatch(BATCH)).thenReturn(BATCH);

        scheduler.cleanupExpired();

        // 101번째 호출 후 break → 최대 101번
        verify(photoService, atMost(101)).deleteExpiredBatch(BATCH);
        // 무한루프가 걸리지 않고 여기까지 도달해야 통과
    }

    @Test
    @DisplayName("cleanupExpired — batchSize 값이 deleteExpiredBatch 인자로 그대로 전달됨")
    void cleanup_batchSizePassedToService() {
        int customBatch = 42;
        ReflectionTestUtils.setField(scheduler, "batchSize", customBatch);
        when(photoService.deleteExpiredBatch(customBatch)).thenReturn(0);

        scheduler.cleanupExpired();

        verify(photoService).deleteExpiredBatch(customBatch);
    }
}

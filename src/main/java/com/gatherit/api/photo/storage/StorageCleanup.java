package com.gatherit.api.photo.storage;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 스토리지 파일 삭제를 "DB 커밋 이후"로 미루는 헬퍼 (엣지 감사 P1-5).
 *
 * 왜:
 *  1. 풀 점유 — S3 delete(외부 블로킹 I/O, 호출당 최대 30s)를 @Transactional 안에서 루프로 돌리면
 *     사진 많은 계정 탈퇴/그룹 삭제 1건이 Hikari 커넥션(5개)을 분 단위로 점유할 수 있다.
 *     업로드(uploadBatch)에서 고친 원칙(#27 — 외부 I/O는 tx 밖)의 삭제 경로 버전.
 *  2. 정합성 — S3 삭제는 롤백이 안 된다. tx 안에서 지우면 중간 실패 시 "DB 행은 복구됐는데
 *     원본 파일만 사라진" 깨진 사진이 남는다. 커밋 성공이 확정된 뒤에만 지우는 게 안전.
 *
 * 사용: tx 안에서는 key 만 모으고 deleteAfterCommit(storage, keys) 호출.
 *  - 활성 tx 가 있으면 afterCommit 에 등록 (커밋 후 같은 스레드에서 실행 — DB 커넥션은 이미 반환됨)
 *  - 없으면 즉시 삭제 (테스트/비트랜잭션 경로 폴백)
 *  - deleteSafe 라 개별 실패는 로그만 남고 무시됨. 누락분은 S3 lifecycle(7일)이 최종 안전망.
 */
public final class StorageCleanup {

    private StorageCleanup() {}

    public static void deleteAfterCommit(PhotoStorage storage, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<String> copy = new ArrayList<>(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String k : copy) storage.deleteSafe(k);
                }
            });
        } else {
            for (String k : copy) storage.deleteSafe(k);
        }
    }
}

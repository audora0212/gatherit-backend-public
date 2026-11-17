package com.gatherit.api.ai.repository;

import com.gatherit.api.ai.entity.AiImportLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AiImportLogRepository extends JpaRepository<AiImportLog, Long> {

    /** 한국 시간 자정 이후 성공한 일정 추출 횟수. */
    int countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);
}

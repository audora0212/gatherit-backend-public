package com.gatherit.api.ai.repository;

import com.gatherit.api.ai.entity.AiCommitReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiCommitReceiptRepository extends JpaRepository<AiCommitReceipt, Long> {

    Optional<AiCommitReceipt> findByUserIdAndIdemKey(Long userId, String idemKey);
}

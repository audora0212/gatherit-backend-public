package com.gatherit.api.photo.repository;

import com.gatherit.api.photo.entity.PhotoUploadReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PhotoUploadReceiptRepository extends JpaRepository<PhotoUploadReceipt, Long> {

    Optional<PhotoUploadReceipt> findByUserIdAndBatchId(Long userId, String batchId);
}

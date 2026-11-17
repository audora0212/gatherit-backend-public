package com.gatherit.api.notification.repository;

import com.gatherit.api.notification.entity.Notification;
import com.gatherit.api.notification.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /** 알림 목록 상한 — 헤비유저 전건 로딩 방지 (P2-9). 오래된 알림은 자연히 밀려난다. */
    List<Notification> findTop200ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 전체 읽음 — 행 단위 dirty checking 대신 한 방 UPDATE (P2-9). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllReadByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** 죽은 참조 알림 일괄 삭제 — poll/event 하드 삭제 시 모든 수신자의 해당 refKey 알림 제거(v1.3.1 #1). */
    void deleteAllByRefKeyIn(Collection<String> refKeys);

    /** 특정 수신자의 해당 refKey 알림만 삭제 — 그룹 탈퇴/강퇴 시 그 멤버가 받은 group:/photo: 알림 정리(엣지 감사 P2-1). */
    void deleteAllByUserIdAndRefKeyIn(Long userId, Collection<String> refKeys);

    /** 탈퇴 유저의 수신 알림 전부 제거. */
    void deleteAllByUserId(Long userId);

    /**
     * 사진 업로드 같은 묶음 가능한 type 의 push dedup 용 —
     * 지정 시점 이후에 같은 user/type/refKey 알림이 있으면 true.
     */
    boolean existsByUserIdAndTypeAndRefKeyAndCreatedAtAfter(
        Long userId, NotificationType type, String refKey, Instant since);

    /** 위의 일괄판 — fan-out 시 dedup 대상 유저를 한 쿼리로 (P2-8). */
    List<Notification> findAllByUserIdInAndTypeAndRefKeyAndCreatedAtAfter(
        Collection<Long> userIds, NotificationType type, String refKey, Instant since);
}

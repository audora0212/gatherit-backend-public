package com.gatherit.api.notification.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@Table(name = "notifications",
    indexes = {
        @Index(name = "idx_notif_user_unread", columnList = "user_id, read_at"),
        @Index(name = "idx_notif_user_created", columnList = "user_id, created_at"),
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String subtitle;

    /** 클릭 시 이동할 reference (e.g. event:42, poll:7, group:3) */
    @Column(name = "ref_key", length = 50)
    private String refKey;

    @Column(name = "read_at")
    private Instant readAt;

    @Builder
    private Notification(Long userId, NotificationType type, String title, String subtitle, String refKey) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.subtitle = subtitle;
        this.refKey = refKey;
    }

    public void markRead() {
        if (this.readAt == null) this.readAt = Instant.now();
    }

    public boolean isRead() {
        return this.readAt != null;
    }
}

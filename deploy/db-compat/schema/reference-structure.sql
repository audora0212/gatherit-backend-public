CREATE TABLE `ai_commit_receipts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `created_event_ids` varchar(1000) NOT NULL,
  `idem_key` varchar(64) NOT NULL,
  `requested` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_commit_user_key` (`user_id`,`idem_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_import_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_import_user_created` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `announcements` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `body` text NOT NULL,
  `ends_at` datetime(6) DEFAULT NULL,
  `starts_at` datetime(6) NOT NULL,
  `title` varchar(100) NOT NULL,
  `type` enum('MAINTENANCE','NOTICE','UPDATE') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `dday_pins` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `event_id` bigint NOT NULL,
  `is_primary` bit(1) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_dday_user_event` (`user_id`,`event_id`),
  KEY `idx_dday_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `event_groups` (
  `event_id` bigint NOT NULL,
  `group_id` bigint NOT NULL,
  PRIMARY KEY (`event_id`,`group_id`),
  KEY `idx_event_groups_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `color_key` enum('cert','club','family','green','lemon','pink','sky','team','wine') DEFAULT NULL,
  `created_by` bigint NOT NULL,
  `end_at` datetime(6) NOT NULL,
  `from_poll_id` bigint DEFAULT NULL,
  `location` varchar(200) DEFAULT NULL,
  `memo` varchar(1000) DEFAULT NULL,
  `shadow_of_event_id` bigint DEFAULT NULL,
  `start_at` datetime(6) NOT NULL,
  `title` varchar(100) NOT NULL,
  `rrule` varchar(200) DEFAULT NULL,
  `all_day` tinyint(1) NOT NULL DEFAULT '0',
  `import_uid` varchar(255) DEFAULT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `place_name` varchar(100) DEFAULT NULL,
  `reminder_all_day_days` int DEFAULT NULL,
  `reminder_minutes_before` int DEFAULT NULL,
  `road_address` varchar(200) DEFAULT NULL,
  `exdates` varchar(2000) DEFAULT NULL,
  `recurrence_id` datetime(6) DEFAULT NULL,
  `recurrence_parent_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_events_start_at` (`start_at`),
  KEY `idx_events_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `group_members` (
  `group_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `color_key` enum('cert','club','family','green','lemon','pink','sky','team','wine') DEFAULT NULL,
  `role` enum('MEMBER','OWNER') NOT NULL,
  PRIMARY KEY (`group_id`,`user_id`),
  KEY `idx_group_members_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `groups_` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `color_key` enum('cert','club','family','green','lemon','pink','sky','team','wine') NOT NULL,
  `created_by` bigint NOT NULL,
  `event_create_policy` enum('ANYONE','OWNER_ONLY') NOT NULL,
  `invite_policy` enum('ANYONE','OWNER_ONLY') NOT NULL,
  `name` varchar(50) NOT NULL,
  `personal` bit(1) NOT NULL,
  `photo_delete_policy` enum('ANYONE','UPLOADER_ONLY') NOT NULL,
  `poll_create_policy` enum('ANYONE','OWNER_ONLY') NOT NULL,
  `public_poll_join_allowed` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notifications` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `ref_key` varchar(50) DEFAULT NULL,
  `subtitle` varchar(500) DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `type` enum('EVENT_CREATED','EVENT_REMINDER','EVENT_UPDATED','GROUP_JOINED_BY','PHOTO_EXPIRING','PHOTO_UPLOADED','POLL_COMPLETED','POLL_CONFIRMED','POLL_CREATED','POLL_DEADLINE_SOON') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notif_user_unread` (`user_id`,`read_at`),
  KEY `idx_notif_user_created` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `photo_upload_receipts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `batch_id` varchar(64) NOT NULL,
  `photo_ids` varchar(1000) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_photo_upload_user_batch` (`user_id`,`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `photos` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `group_id` bigint NOT NULL,
  `mime_type` varchar(50) NOT NULL,
  `original_name` varchar(200) DEFAULT NULL,
  `size_bytes` bigint DEFAULT NULL,
  `s3_key` varchar(500) NOT NULL,
  `uploaded_at` datetime(6) NOT NULL,
  `uploaded_by` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_photos_group` (`group_id`),
  KEY `idx_photos_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `poll_guest_participants` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `edit_token_hash` varchar(64) NOT NULL,
  `handoff_code_hash` varchar(64) DEFAULT NULL,
  `handoff_expires_at` datetime(6) DEFAULT NULL,
  `linked_user_id` bigint DEFAULT NULL,
  `nickname` varchar(20) NOT NULL,
  `poll_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poll_guest_nickname` (`poll_id`,`nickname`),
  UNIQUE KEY `uk_poll_guest_edit_token` (`poll_id`,`edit_token_hash`),
  UNIQUE KEY `uk_poll_guest_linked_user` (`poll_id`,`linked_user_id`),
  UNIQUE KEY `uk_poll_guest_handoff_code` (`handoff_code_hash`),
  KEY `idx_poll_guest_participants_poll` (`poll_id`),
  KEY `idx_poll_guest_participants_linked_user` (`linked_user_id`),
  CONSTRAINT `FK9aan2yoooiitr2cirecsmmrt0` FOREIGN KEY (`poll_id`) REFERENCES `polls` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `poll_guest_votes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slot_id` bigint NOT NULL,
  `participant_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_poll_guest_vote_participant_slot` (`participant_id`,`slot_id`),
  KEY `idx_poll_guest_votes_participant` (`participant_id`),
  KEY `idx_poll_guest_votes_slot` (`slot_id`),
  CONSTRAINT `FKpcyph78y6gfd3l9s3macfblcd` FOREIGN KEY (`participant_id`) REFERENCES `poll_guest_participants` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `poll_slots` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `end_at` datetime(6) NOT NULL,
  `poll_id` bigint NOT NULL,
  `start_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_poll_slots_poll` (`poll_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `poll_votes` (
  `poll_id` bigint NOT NULL,
  `slot_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `available` bit(1) NOT NULL,
  PRIMARY KEY (`poll_id`,`slot_id`,`user_id`),
  KEY `idx_poll_votes_poll_user` (`poll_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `polls` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `confirm_policy` enum('ANYONE','PROPOSER_ONLY') NOT NULL,
  `confirmed_slot_id` bigint DEFAULT NULL,
  `created_by` bigint NOT NULL,
  `deadline_at` datetime(6) NOT NULL,
  `group_id` bigint NOT NULL,
  `linked_event_id` bigint DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  `version` bigint NOT NULL,
  `deadline_notified` tinyint(1) NOT NULL DEFAULT '0',
  `public_share_expires_at` datetime(6) DEFAULT NULL,
  `public_share_token` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_polls_public_share_token` (`public_share_token`),
  KEY `idx_polls_group` (`group_id`),
  KEY `idx_polls_deadline` (`deadline_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `popups` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `body` text NOT NULL,
  `enabled` bit(1) NOT NULL,
  `ends_at` datetime(6) DEFAULT NULL,
  `rule` enum('DAILY','ONCE') NOT NULL,
  `starts_at` datetime(6) NOT NULL,
  `target_below_version` varchar(20) DEFAULT NULL,
  `title` varchar(100) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refresh_tokens` (
  `user_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `token` varchar(1024) NOT NULL,
  `previous_token` varchar(1024) DEFAULT NULL,
  `previous_token_expires_at` datetime(6) DEFAULT NULL,
  `recovery_key_hash` varchar(64) DEFAULT NULL,
  `recovery_expires_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_group_notif_prefs` (
  `group_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `enabled` bit(1) NOT NULL,
  `event_created` bit(1) NOT NULL,
  `event_updated` bit(1) NOT NULL,
  `group_joined_by` bit(1) NOT NULL,
  `photo_expiring` bit(1) NOT NULL,
  `photo_uploaded` bit(1) NOT NULL,
  `poll_confirmed` bit(1) NOT NULL,
  `poll_created` bit(1) NOT NULL,
  `poll_deadline_soon` bit(1) NOT NULL,
  PRIMARY KEY (`group_id`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_notif_prefs` (
  `user_id` bigint NOT NULL,
  `all_off` bit(1) NOT NULL,
  `lead_time_minutes` int NOT NULL,
  `quiet_hours_enabled` bit(1) NOT NULL,
  `quiet_hours_end` int NOT NULL,
  `quiet_hours_start` int NOT NULL,
  `all_day_reminder_days` int NOT NULL DEFAULT '1',
  `poll_completed_enabled` tinyint(1) NOT NULL DEFAULT '1',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `email` varchar(200) DEFAULT NULL,
  `nickname` varchar(50) NOT NULL,
  `oauth_id` varchar(100) DEFAULT NULL,
  `oauth_provider` enum('APPLE','GOOGLE','KAKAO','LOCAL') DEFAULT NULL,
  `password_hash` varchar(100) DEFAULT NULL,
  `profile_image_url` varchar(500) DEFAULT NULL,
  `username` varchar(50) DEFAULT NULL,
  `role` varchar(20) NOT NULL DEFAULT 'USER',
  `expo_push_token` varchar(200) DEFAULT NULL,
  `ical_token` varchar(64) DEFAULT NULL,
  `personal_color` varchar(7) DEFAULT NULL,
  `push_platform` varchar(10) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_username` (`username`),
  UNIQUE KEY `uk_users_oauth` (`oauth_provider`,`oauth_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

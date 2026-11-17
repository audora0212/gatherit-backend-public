package com.gatherit.api.notification.repository;

import com.gatherit.api.notification.entity.UserNotifPrefs;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotifPrefsRepository extends JpaRepository<UserNotifPrefs, Long> {}

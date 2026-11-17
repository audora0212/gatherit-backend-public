package com.gatherit.api.notification.repository;

import com.gatherit.api.notification.entity.UserGroupNotifPref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserGroupNotifPrefRepository extends JpaRepository<UserGroupNotifPref, UserGroupNotifPref.PK> {
    List<UserGroupNotifPref> findAllByUserId(Long userId);
    Optional<UserGroupNotifPref> findByUserIdAndGroupId(Long userId, Long groupId);

    /** fan-out 일괄 판정용 — 대상 유저들의 컨텍스트 그룹 prefs 를 한 번에 (P2-8). */
    List<UserGroupNotifPref> findAllByUserIdInAndGroupIdIn(Collection<Long> userIds, Collection<Long> groupIds);
    void deleteAllByUserId(Long userId);
    void deleteByUserIdAndGroupId(Long userId, Long groupId);
}

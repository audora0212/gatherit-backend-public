package com.gatherit.api.group.repository;

import com.gatherit.api.group.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GroupRepository extends JpaRepository<Group, Long> {

    /**
     * 사용자가 속한 그룹 전체 — group_members 와 join.
     * 추후 그룹 카드 정렬/last_event_at 로 정렬 시 다시 손볼 부분.
     */
    @Query("""
        select g from Group g
        where g.id in (
            select gm.groupId from GroupMember gm where gm.userId = :userId
        )
        order by g.id desc
    """)
    List<Group> findAllByMemberUserId(Long userId);
}

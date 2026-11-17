package com.gatherit.api.group.repository;

import com.gatherit.api.group.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMember.PK> {

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    List<GroupMember> findAllByGroupId(Long groupId);

    List<GroupMember> findAllByUserId(Long userId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    /** fan-out 일괄 판정용 — 대상 유저들의 컨텍스트 그룹 멤버십을 한 번에 (P2-8). */
    List<GroupMember> findAllByGroupIdInAndUserIdIn(Collection<Long> groupIds, Collection<Long> userIds);

    long countByGroupId(Long groupId);

    long countByUserId(Long userId);

    /** MY 화면 GROUPS 카운트 — 회원가입 시 자동 생성된 "내 캘린더" 개인 그룹은 제외. */
    @Query("select count(gm) from GroupMember gm join Group g on g.id = gm.groupId where gm.userId = :userId and g.personal = false")
    long countByUserIdExcludingPersonal(@Param("userId") Long userId);

    void deleteByGroupId(Long groupId);
}

package com.gatherit.api.photo.repository;

import com.gatherit.api.photo.entity.Photo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    List<Photo> findAllByGroupIdOrderByUploadedAtDesc(Long groupId);

    /** 그룹당 사진 수 — quota(100장) 검사. */
    long countByGroupId(Long groupId);

    /** 떠나기/강퇴 cleanup — 본인이 그 그룹에 올린 사진. */
    List<Photo> findAllByUploadedByAndGroupId(Long uploadedBy, Long groupId);

    /** 그룹 삭제 cleanup — 그 그룹의 모든 사진. */
    List<Photo> findAllByGroupId(Long groupId);

    /** 회원탈퇴 cleanup — 본인이 올린 모든 사진. */
    List<Photo> findAllByUploadedBy(Long uploadedBy);

    /** MY 탭 stats — 사용자가 속한 비-개인 그룹의 사진 합계. */
    @Query("""
        select count(p) from Photo p
        where p.groupId in (
            select g.id from com.gatherit.api.group.entity.Group g
            join com.gatherit.api.group.entity.GroupMember gm on gm.groupId = g.id
            where gm.userId = :userId and g.personal = false
        )
    """)
    long countSharedPhotosForUser(Long userId);

    /** 만료 cron — 페이징으로 lock 폭주 방지 */
    @Query("select p from Photo p where p.expiresAt <= :now")
    List<Photo> findExpiredBatch(Instant now, Pageable pageable);
}

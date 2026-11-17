package com.gatherit.api.auth.repository;

import com.gatherit.api.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 만료된 토큰 일괄 삭제 — 스케줄 cleanup 용. */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}

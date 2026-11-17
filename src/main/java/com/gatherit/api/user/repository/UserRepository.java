package com.gatherit.api.user.repository;

import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<User> findByOauthProviderAndOauthId(OauthProvider provider, String oauthId);

    /** iCal 구독 피드 — URL 토큰으로 사용자 식별. */
    Optional<User> findByIcalToken(String icalToken);

    /** 외부 소셜사진(말랑이 아님)을 가진 OAuth 사용자 — 프로필 말랑이 통일 마이그레이션용. */
    @Query("select u from User u where u.oauthProvider in :providers "
            + "and u.profileImageUrl is not null and u.profileImageUrl not like 'mallang:%'")
    List<User> findOAuthUsersWithExternalAvatar(@Param("providers") List<OauthProvider> providers);
}

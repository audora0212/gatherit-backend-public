package com.gatherit.api.user;

import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 일회성 마이그레이션 — KAKAO/GOOGLE 가입자 중 소셜 제공사 사진을 쓰던 기존 유저의 프로필을
 * 기본 말랑이 캐릭터로 전환한다.
 *
 * idempotent — 마이그 후엔 대상 0건이라 매 부팅 시 쿼리 1회로 끝남.
 * 신규 가입자는 AuthService 가 이미 randomMallang() 부여 → 추가 대상 없음.
 * 사용자가 직접 고르는 프사도 "mallang:*" 라 사용자 선택을 덮어쓰지 않음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthAvatarMigration implements ApplicationRunner {

    private final UserRepository userRepository;

    private static final String[] MALLANG = {
            "smile", "wink", "love", "calm", "pouty", "jealous", "sad", "mean"
    };

    @Override
    public void run(ApplicationArguments args) {
        List<User> targets = userRepository.findOAuthUsersWithExternalAvatar(
                List.of(OauthProvider.KAKAO, OauthProvider.GOOGLE));
        if (targets.isEmpty()) return;
        for (User u : targets) {
            u.updateProfileImage("mallang:" + MALLANG[(int) (Math.random() * MALLANG.length)]);
        }
        userRepository.saveAll(targets);
        log.info("[OAuthAvatarMigration] OAuth 소셜사진 사용자 {}명 → 랜덤 말랑이로 통일", targets.size());
    }
}

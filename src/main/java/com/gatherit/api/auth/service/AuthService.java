package com.gatherit.api.auth.service;

import com.gatherit.api.auth.dto.AppleLoginRequest;
import com.gatherit.api.auth.dto.AppleUserInfo;
import com.gatherit.api.auth.dto.AuthResponse;
import com.gatherit.api.auth.dto.GoogleLoginRequest;
import com.gatherit.api.auth.dto.GoogleUserInfo;
import com.gatherit.api.auth.dto.KakaoLoginRequest;
import com.gatherit.api.auth.dto.KakaoTokenResponse;
import com.gatherit.api.auth.dto.KakaoUserInfo;
import com.gatherit.api.auth.dto.LoginRequest;
import com.gatherit.api.auth.dto.LogoutRequest;
import com.gatherit.api.auth.dto.RefreshRequest;
import com.gatherit.api.auth.dto.SignupRequest;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final RefreshTokenStore refreshTokenStore;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final AppleOAuthClient appleOAuthClient;
    private final GoogleOAuthClient googleOAuthClient;
    private final GroupService groupService;
    private final AuthMetrics authMetrics;

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        // BCrypt now rejects new hashes above 72 UTF-8 bytes. Do not apply this
        // creation-only limit to login: legacy hashes must remain verifiable.
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다");
        }
        if (userRepository.existsByUsername(req.username())) {
            throw new CustomException(ErrorCode.USERNAME_ALREADY_USED);
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_USED);
        }
        User user = User.builder()
                .username(req.username())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .nickname(req.nickname())
                .profileImageUrl(randomMallang())
                .oauthProvider(OauthProvider.LOCAL)
                .build();
        userRepository.save(user);
        // 신규 가입 사용자에게 "내 캘린더" 개인 그룹 자동 생성
        groupService.createPersonalGroup(user.getId());
        return issueTokens(user, true);
    }

    /** 신규 가입 시 본인 프로필 — 8가지 표정 말랑이 중 랜덤. FE 가 "mallang:{name}" → 로컬 asset 매핑. */
    private static final String[] MALLANG_NAMES = {
            "smile", "wink", "love", "calm", "pouty", "jealous", "sad", "mean"
    };

    private String randomMallang() {
        int idx = (int) (Math.random() * MALLANG_NAMES.length);
        return "mallang:" + MALLANG_NAMES[idx];
    }

    /**
     * OAuth 가입자가 이름/닉네임 정보를 못 받았을 때 (Apple 첫 로그인 fullName 누락 등)
     * 사용할 fallback 닉네임. 모두에게 "사용자" 를 부여하면 사람을 구분 못 하므로 4자리 숫자 suffix.
     * 충돌 가능성은 낮지만 unique 보장은 아님 — UI 상 식별 용도일 뿐이고 본인은 MY 화면에서 변경 가능.
     */
    private String fallbackNickname() {
        int n = (int) (Math.random() * 9000) + 1000;
        return "사용자 " + n;
    }

    // 로그인 시 갱신 세션을 저장하므로 읽기·쓰기 트랜잭션이 필요하다.
    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.username())
                .orElseThrow(() -> new CustomException(ErrorCode.LOGIN_FAILED));
        if (user.getPasswordHash() == null) {
            // OAuth 가입 사용자는 아이디/비밀번호 로그인 불가
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }
        return issueTokens(user);
    }

    /** Refresh keeps one active session; the store atomically rotates or recovers the same attempt. */
    @Transactional
    public AuthResponse refresh(RefreshRequest req) {
        if (!jwtUtils.validateRefreshToken(req.refreshToken())) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        Long userId = jwtUtils.getUserId(req.refreshToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        try {
            RefreshTokenStore.RotationResult result = refreshTokenStore.refresh(
                    userId, req.refreshToken(), req.refreshAttemptId());
            authMetrics.recordRefresh(result.outcome());
            String access = jwtUtils.generateAccessToken(user.getId(), user.getEmail());
            return new AuthResponse(access, result.token(), userSummary(user), false);
        } catch (CustomException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_TOKEN) {
                authMetrics.recordRefresh("forced_logout");
                log.warn("refresh 거절 — 현재 세션 또는 유효한 재시도와 불일치: userId={}", userId);
            }
            throw e;
        }
    }

    @Transactional
    public void logout(Long userId) {
        logout(userId, null);
    }

    @Transactional
    public void logout(Long userId, LogoutRequest request) {
        if (request == null) {
            // Body-less old app versions keep their original behavior until they upgrade.
            refreshTokenStore.invalidate(userId);
        } else if (!refreshTokenStore.invalidateMatching(userId, request.refreshToken(), request.refreshAttemptId())) {
            return; // Newer login owns this session and push token; a stale logout is a successful no-op.
        }
        // 푸시 토큰 비우기 — 로그아웃한 기기로 계속 푸시가 가는 유령 알림 방지(엣지 감사 P2-5).
        // 재로그인 시 앱이 콜드스타트에서 토큰을 다시 등록하므로 안전. (앱 logout 이 이 API 를 호출함)
        userRepository.findById(userId).ifPresent(u -> {
            if (u.getExpoPushToken() != null) {
                u.updateExpoPushToken(null);
                userRepository.save(u);
            }
        });
    }

    /**
     * 카카오 로그인 — FE 가 받은 code 로 카카오 토큰 교환 → 사용자 정보 조회 → 우리 JWT 발급.
     * 동일 카카오 ID 가 처음이면 자동 가입 (OAuth-first 패턴).
     */
    @Transactional
    public AuthResponse loginWithKakao(KakaoLoginRequest req) {
        KakaoTokenResponse token = kakaoOAuthClient.exchangeToken(req.code(), req.redirectUri());
        KakaoUserInfo info = kakaoOAuthClient.fetchUserInfo(token.accessToken());
        if (info == null || info.id() == null) {
            throw new CustomException(ErrorCode.KAKAO_AUTH_FAILED);
        }

        String oauthId = String.valueOf(info.id());
        boolean[] isNew = {false};
        User user = userRepository.findByOauthProviderAndOauthId(OauthProvider.KAKAO, oauthId)
                .orElseGet(() -> {
                    User newUser = registerKakaoUser(info, oauthId);
                    // 신규 카카오 가입자에게도 "내 캘린더" 자동 생성
                    groupService.createPersonalGroup(newUser.getId());
                    isNew[0] = true;
                    return newUser;
                });

        return issueTokens(user, isNew[0]);
    }

    private User registerKakaoUser(KakaoUserInfo info, String oauthId) {
        String email = null;
        String nickname = fallbackNickname();
        String profileImageUrl = null;

        if (info.kakaoAccount() != null) {
            email = info.kakaoAccount().email();
            if (info.kakaoAccount().profile() != null) {
                String kakaoNickname = info.kakaoAccount().profile().nickname();
                if (kakaoNickname != null && !kakaoNickname.isBlank()) {
                    nickname = kakaoNickname;
                }
            }
        }

        // 카카오 이메일이 이미 로컬 가입 사용자 이메일과 충돌하면 NULL 처리 (충돌 방지)
        if (email != null && userRepository.existsByEmail(email)) {
            email = null;
        }

        // 프로필 사진은 카카오 사진 대신 항상 랜덤 말랑이로 통일 (브랜드 일관성)
        profileImageUrl = randomMallang();

        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .nickname(nickname)
                .profileImageUrl(profileImageUrl)
                .oauthProvider(OauthProvider.KAKAO)
                .oauthId(oauthId)
                .build();
        return userRepository.save(user);
    }

    /**
     * Apple 로그인 — 앱의 expo-apple-authentication 으로 받은 identityToken 검증 → 우리 JWT 발급.
     * fullName 은 첫 로그인 시에만 전달됨 → 가입 시점 닉네임 fallback.
     */
    @Transactional
    public AuthResponse loginWithApple(AppleLoginRequest req) {
        AppleUserInfo info = appleOAuthClient.verifyIdentityToken(req.identityToken());
        String oauthId = info.sub();
        boolean[] isNew = {false};
        User user = userRepository.findByOauthProviderAndOauthId(OauthProvider.APPLE, oauthId)
                .orElseGet(() -> {
                    User newUser = registerAppleUser(info, oauthId, req.fullName());
                    groupService.createPersonalGroup(newUser.getId());
                    isNew[0] = true;
                    return newUser;
                });
        return issueTokens(user, isNew[0]);
    }

    private User registerAppleUser(AppleUserInfo info, String oauthId, String fullName) {
        String email = info.email();
        // Apple email 이 이미 다른 계정에 사용 중이면 NULL 처리 (Apple private relay 충돌 회피)
        if (email != null && userRepository.existsByEmail(email)) {
            email = null;
        }
        String nickname = (fullName != null && !fullName.isBlank()) ? fullName : fallbackNickname();
        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .nickname(nickname)
                .profileImageUrl(randomMallang())
                .oauthProvider(OauthProvider.APPLE)
                .oauthId(oauthId)
                .build();
        return userRepository.save(user);
    }

    /**
     * Google 로그인 — 앱의 Google Sign In 으로 받은 idToken 검증 → 우리 JWT 발급.
     */
    @Transactional
    public AuthResponse loginWithGoogle(GoogleLoginRequest req) {
        GoogleUserInfo info = googleOAuthClient.verifyIdToken(req.idToken());
        String oauthId = info.sub();
        boolean[] isNew = {false};
        User user = userRepository.findByOauthProviderAndOauthId(OauthProvider.GOOGLE, oauthId)
                .orElseGet(() -> {
                    User newUser = registerGoogleUser(info, oauthId);
                    groupService.createPersonalGroup(newUser.getId());
                    isNew[0] = true;
                    return newUser;
                });
        return issueTokens(user, isNew[0]);
    }

    private User registerGoogleUser(GoogleUserInfo info, String oauthId) {
        String email = info.email();
        if (email != null && userRepository.existsByEmail(email)) {
            email = null;
        }
        String nickname = (info.name() != null && !info.name().isBlank()) ? info.name() : fallbackNickname();
        // 프로필 사진은 구글 사진 대신 항상 랜덤 말랑이로 통일 (브랜드 일관성)
        String profileImageUrl = randomMallang();
        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .nickname(nickname)
                .profileImageUrl(profileImageUrl)
                .oauthProvider(OauthProvider.GOOGLE)
                .oauthId(oauthId)
                .build();
        return userRepository.save(user);
    }

    private AuthResponse issueTokens(User user) {
        return issueTokens(user, false);
    }

    private AuthResponse issueTokens(User user, boolean isNewUser) {
        String access = jwtUtils.generateAccessToken(user.getId(), user.getEmail());
        String refresh = jwtUtils.generateRefreshToken(user.getId());
        // 세션 바인딩 — 기존 refresh 덮어쓰기 (1 user 1 active session)
        refreshTokenStore.save(user.getId(), refresh);
        return new AuthResponse(access, refresh, userSummary(user), isNewUser);
    }

    private AuthResponse.UserSummary userSummary(User user) {
        return new AuthResponse.UserSummary(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getOauthProvider(),
                user.getPersonalColor(),
                user.getRole()
        );
    }
}

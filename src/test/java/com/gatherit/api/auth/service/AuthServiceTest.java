package com.gatherit.api.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.auth.controller.AuthController;
import com.gatherit.api.common.notify.SlackNotifier;
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
import com.gatherit.api.exception.GlobalExceptionHandler;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthService 단위 테스트.
 * 정상 경로(토큰 발급/세션 바인딩) + 에러 분기(각 ErrorCode) + OAuth 등록 분기 커버.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final long USER_ID = 1L;

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtils jwtUtils;
    @Mock RefreshTokenStore refreshTokenStore;
    @Mock KakaoOAuthClient kakaoOAuthClient;
    @Mock AppleOAuthClient appleOAuthClient;
    @Mock GoogleOAuthClient googleOAuthClient;
    @Mock GroupService groupService;
    @Mock AuthMetrics authMetrics;

    @InjectMocks AuthService authService;

    // ---- helpers ----

    private User localUser() {
        User u = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("$2a$10$hashed")
                .nickname("테스터")
                .profileImageUrl("mallang:smile")
                .oauthProvider(OauthProvider.LOCAL)
                .build();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private User oauthUser(OauthProvider provider, String oauthId) {
        User u = User.builder()
                .email("oauth@example.com")
                .nickname("소셜유저")
                .profileImageUrl("mallang:calm")
                .oauthProvider(provider)
                .oauthId(oauthId)
                .build();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ============================================================
    // signup
    // ============================================================

    @Test
    @DisplayName("signup — 정상: 토큰 발급 + 개인그룹 생성 + isNewUser=true")
    void signup_success() {
        SignupRequest req = new SignupRequest("newuser", "new@example.com", "password123", "뉴비");
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        User saved = User.builder()
                .username("newuser").email("new@example.com")
                .passwordHash("hashed").nickname("뉴비")
                .profileImageUrl("mallang:smile").oauthProvider(OauthProvider.LOCAL)
                .build();
        ReflectionTestUtils.setField(saved, "id", USER_ID);
        // signup 은 save 반환이 아닌 원본 user 를 그대로 사용 → JPA IDENTITY 처럼 인자에 id 주입(실 동작 모사)
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(jwtUtils.generateAccessToken(USER_ID, "new@example.com")).thenReturn("access-token");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh-token");

        AuthResponse resp = authService.signup(req);

        assertThat(resp.accessToken()).isEqualTo("access-token");
        assertThat(resp.refreshToken()).isEqualTo("refresh-token");
        assertThat(resp.isNewUser()).isTrue();
        verify(groupService).createPersonalGroup(USER_ID);
        verify(refreshTokenStore).save(eq(USER_ID), eq("refresh-token"));
    }

    @Test
    @DisplayName("signup — username 중복이면 USERNAME_ALREADY_USED")
    void signup_duplicateUsername() {
        SignupRequest req = new SignupRequest("dupuser", "new@example.com", "pw", "닉");
        when(userRepository.existsByUsername("dupuser")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.USERNAME_ALREADY_USED);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup — email 중복이면 EMAIL_ALREADY_USED")
    void signup_duplicateEmail() {
        SignupRequest req = new SignupRequest("newuser2", "dup@example.com", "pw", "닉");
        when(userRepository.existsByUsername("newuser2")).thenReturn(false);
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.EMAIL_ALREADY_USED);

        verify(userRepository, never()).save(any());
    }

    static Stream<Arguments> overlengthNewPasswords() {
        return Stream.of(Arguments.of("ASCII 73 bytes", "a".repeat(73)),
                Arguments.of("ASCII 100 bytes", "a".repeat(100)),
                Arguments.of("Korean 75 bytes", "\uAC00".repeat(25)),
                Arguments.of("Emoji 76 bytes", "\uD83D\uDE00".repeat(19)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlengthNewPasswords")
    void signup_over72Bytes_rejectsBeforeAnySideEffects(String label, String password) {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("newuser", "new@example.com", password, "test")))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verifyNoInteractions(userRepository, passwordEncoder, groupService, jwtUtils, refreshTokenStore);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlengthNewPasswords")
    void signup_over72Bytes_returns400WithoutPasswordInResponse(String label, String password) throws Exception {
        SlackNotifier slack = mock(SlackNotifier.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler(slack)).build();
        byte[] body = new ObjectMapper().writeValueAsBytes(
                new SignupRequest("newuser", "new@example.com", password, "test"));

        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"))
                .andExpect(jsonPath("$.message").value("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다"));
        verifyNoInteractions(userRepository, passwordEncoder, groupService, jwtUtils, refreshTokenStore, slack);
    }

    static Stream<Arguments> supportedNewPasswords() {
        return Stream.of(Arguments.of("ASCII 72 bytes", "a".repeat(72)),
                Arguments.of("Korean 72 bytes", "\uAC00".repeat(24)),
                Arguments.of("Emoji 72 bytes", "\uD83D\uDE00".repeat(18)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedNewPasswords")
    void signup_exact72Bytes_remainsSupported(String label, String password) {
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", USER_ID);
            return user;
        });
        when(jwtUtils.generateAccessToken(USER_ID, "new@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse result = authService.signup(new SignupRequest("newuser", "new@example.com", password, "test"));

        assertThat(result.accessToken()).isEqualTo("access");
        verify(passwordEncoder).encode(password);
        verify(groupService).createPersonalGroup(USER_ID);
        verify(refreshTokenStore).save(USER_ID, "refresh");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overlengthNewPasswords")
    void login_doesNotApplyNewSignupLimitToLegacyPasswords(String label, String password) {
        User user = localUser();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, user.getPasswordHash())).thenReturn(true);
        when(jwtUtils.generateAccessToken(USER_ID, "test@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        assertThat(authService.login(new LoginRequest("testuser", password)).accessToken()).isEqualTo("access");
        verify(passwordEncoder).matches(password, user.getPasswordHash());
    }

    // ============================================================
    // login
    // ============================================================

    @Test
    @DisplayName("login — 정상: 토큰 발급 + isNewUser=false")
    void login_success() {
        LoginRequest req = new LoginRequest("testuser", "password");
        User user = localUser();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "$2a$10$hashed")).thenReturn(true);
        when(jwtUtils.generateAccessToken(USER_ID, "test@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.login(req);

        assertThat(resp.accessToken()).isEqualTo("access");
        assertThat(resp.isNewUser()).isFalse();
        verify(refreshTokenStore).save(USER_ID, "refresh");
    }

    @Test
    @DisplayName("login — 존재하지 않는 username이면 LOGIN_FAILED")
    void login_userNotFound() {
        LoginRequest req = new LoginRequest("ghost", "pw");
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("login — OAuth 가입자(passwordHash=null)는 LOGIN_FAILED")
    void login_oauthUserHasNoPassword() {
        LoginRequest req = new LoginRequest("oauthuser", "pw");
        User oauthUser = User.builder()
                .username("oauthuser").email("o@example.com")
                .passwordHash(null).nickname("소셜")
                .profileImageUrl("mallang:wink").oauthProvider(OauthProvider.KAKAO)
                .build();
        ReflectionTestUtils.setField(oauthUser, "id", USER_ID);
        when(userRepository.findByUsername("oauthuser")).thenReturn(Optional.of(oauthUser));

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    @Test
    @DisplayName("login — 비밀번호 불일치이면 LOGIN_FAILED")
    void login_wrongPassword() {
        LoginRequest req = new LoginRequest("testuser", "wrongpw");
        User user = localUser();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpw", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    // ============================================================
    // refresh
    // ============================================================

    @Test
    @DisplayName("refresh — 정상 회전: 현재 토큰 일치 → 새 access + 새 refresh")
    void refresh_normalRotation() {
        RefreshRequest req = new RefreshRequest("old-refresh");
        User user = localUser();
        when(jwtUtils.validateRefreshToken("old-refresh")).thenReturn(true);
        when(jwtUtils.getUserId("old-refresh")).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenStore.refresh(USER_ID, "old-refresh", null))
                .thenReturn(new RefreshTokenStore.RotationResult("new-refresh", "rotated"));
        when(jwtUtils.generateAccessToken(USER_ID, "test@example.com")).thenReturn("new-access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("new-refresh");

        AuthResponse resp = authService.refresh(req);

        assertThat(resp.accessToken()).isEqualTo("new-access");
        assertThat(resp.refreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenStore).refresh(USER_ID, "old-refresh", null);
    }

    @Test
    @DisplayName("refresh — grace 창 내 직전 토큰 재시도: 회전 없이 현재 세션 복구")
    void refresh_withinGrace() {
        RefreshRequest req = new RefreshRequest("prev-refresh");
        User user = localUser();
        when(jwtUtils.validateRefreshToken("prev-refresh")).thenReturn(true);
        when(jwtUtils.getUserId("prev-refresh")).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenStore.refresh(USER_ID, "prev-refresh", null))
                .thenReturn(new RefreshTokenStore.RotationResult("current-refresh", "grace_recovered"));
        when(jwtUtils.generateAccessToken(USER_ID, "test@example.com")).thenReturn("new-access");

        AuthResponse resp = authService.refresh(req);

        assertThat(resp.accessToken()).isEqualTo("new-access");
        assertThat(resp.refreshToken()).isEqualTo("current-refresh");
        // grace 경로에서는 save(rotate)가 호출되지 않아야 한다
        verify(refreshTokenStore, never()).save(anyLong(), anyString());
    }

    @Test
    @DisplayName("refresh — grace 밖 불일치: 현재 세션 보존 + INVALID_TOKEN")
    void refresh_outsideGrace_rejectsWithoutInvalidatingCurrentSession() {
        RefreshRequest req = new RefreshRequest("stale-refresh");
        User user = localUser();
        when(jwtUtils.validateRefreshToken("stale-refresh")).thenReturn(true);
        when(jwtUtils.getUserId("stale-refresh")).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenStore.refresh(USER_ID, "stale-refresh", null))
                .thenThrow(new CustomException(ErrorCode.INVALID_TOKEN));

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_TOKEN);

        verify(refreshTokenStore, never()).invalidate(USER_ID);
    }

    @Test
    void refresh_attemptProofIsForwardedAndRecoveryIsCounted() {
        String attemptId = "a".repeat(64);
        when(jwtUtils.validateRefreshToken("prev-refresh")).thenReturn(true);
        when(jwtUtils.getUserId("prev-refresh")).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(localUser()));
        when(refreshTokenStore.refresh(USER_ID, "prev-refresh", attemptId))
                .thenReturn(new RefreshTokenStore.RotationResult("current", "attempt_recovered"));
        assertThat(authService.refresh(new RefreshRequest("prev-refresh", attemptId)).refreshToken())
                .isEqualTo("current");
        verify(authMetrics).recordRefresh("attempt_recovered");
    }

    @Test
    @DisplayName("refresh — validateToken 실패(JwtUtils가 CustomException) → 전파")
    void refresh_invalidToken_jwtFails() {
        RefreshRequest req = new RefreshRequest("bad-token");
        when(jwtUtils.validateRefreshToken("bad-token"))
                .thenThrow(new CustomException(ErrorCode.INVALID_TOKEN));

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("refresh — userId 에 해당하는 유저 없으면 USER_NOT_FOUND")
    void refresh_userNotFound() {
        RefreshRequest req = new RefreshRequest("valid-refresh");
        when(jwtUtils.validateRefreshToken("valid-refresh")).thenReturn(true);
        when(jwtUtils.getUserId("valid-refresh")).thenReturn(99L);
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    // ============================================================
    // logout
    // ============================================================

    @Test
    @DisplayName("logout — refreshToken invalidate")
    void logout_invalidatesToken() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        authService.logout(USER_ID);

        verify(refreshTokenStore).invalidate(USER_ID);
    }

    @Test
    @DisplayName("logout — expoPushToken 있으면 null 로 초기화 후 저장")
    void logout_clearsPushToken() {
        User user = localUser();
        user.updateExpoPushToken("ExponentPushToken[abc]");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        authService.logout(USER_ID);

        assertThat(user.getExpoPushToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("logout — expoPushToken 없으면 save 호출 안 함")
    void logout_noPushToken_noSave() {
        User user = localUser(); // expoPushToken = null
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        authService.logout(USER_ID);

        verify(userRepository, never()).save(any());
    }

    @Test
    void boundLogoutOnlyClearsPushTokenWhenSessionMatches() {
        User user = localUser();
        user.updateExpoPushToken("ExponentPushToken[old-session]");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refreshTokenStore.invalidateMatching(USER_ID, "snapshot", null)).thenReturn(true);
        authService.logout(USER_ID, new LogoutRequest("snapshot", null));
        assertThat(user.getExpoPushToken()).isNull();
        verify(userRepository).save(user);
        verify(refreshTokenStore, never()).invalidate(anyLong());
    }

    @Test
    void staleLogoutDoesNotTouchNewSessionPushToken() {
        when(refreshTokenStore.invalidateMatching(USER_ID, "old-snapshot", null)).thenReturn(false);
        authService.logout(USER_ID, new LogoutRequest("old-snapshot", null));
        verifyNoInteractions(userRepository);
        verify(refreshTokenStore, never()).invalidate(anyLong());
    }

    @Test
    void boundLogoutForwardsTheOriginalRecoveryAttempt() {
        String attempt = "a".repeat(64);
        authService.logout(USER_ID, new LogoutRequest("snapshot", attempt));
        verify(refreshTokenStore).invalidateMatching(USER_ID, "snapshot", attempt);
    }

    // ============================================================
    // loginWithKakao
    // ============================================================

    @Test
    @DisplayName("loginWithKakao — 기존 사용자: 토큰 발급 + isNewUser=false")
    void loginWithKakao_existingUser() {
        KakaoLoginRequest req = new KakaoLoginRequest("code123", "https://redirect.uri");
        KakaoTokenResponse token = new KakaoTokenResponse("kakao-access", "Bearer", "kakao-refresh", 3600, "profile");
        KakaoUserInfo info = new KakaoUserInfo(42L, null);
        User user = oauthUser(OauthProvider.KAKAO, "42");

        when(kakaoOAuthClient.exchangeToken("code123", "https://redirect.uri")).thenReturn(token);
        when(kakaoOAuthClient.fetchUserInfo("kakao-access")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.KAKAO, "42"))
                .thenReturn(Optional.of(user));
        when(jwtUtils.generateAccessToken(USER_ID, "oauth@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithKakao(req);

        assertThat(resp.isNewUser()).isFalse();
        verify(groupService, never()).createPersonalGroup(anyLong());
    }

    @Test
    @DisplayName("loginWithKakao — 신규 사용자: 자동 가입 + 개인그룹 생성 + isNewUser=true")
    void loginWithKakao_newUser() {
        KakaoLoginRequest req = new KakaoLoginRequest("code-new", "https://redirect.uri");
        KakaoTokenResponse token = new KakaoTokenResponse("k-access", "Bearer", "k-refresh", 3600, null);
        KakaoUserInfo.Profile profile = new KakaoUserInfo.Profile("카카오닉", null, null);
        KakaoUserInfo.KakaoAccount account = new KakaoUserInfo.KakaoAccount("kakao@example.com", false, profile);
        KakaoUserInfo info = new KakaoUserInfo(77L, account);

        User saved = oauthUser(OauthProvider.KAKAO, "77");
        when(kakaoOAuthClient.exchangeToken("code-new", "https://redirect.uri")).thenReturn(token);
        when(kakaoOAuthClient.fetchUserInfo("k-access")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.KAKAO, "77"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("kakao@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(jwtUtils.generateAccessToken(USER_ID, "oauth@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithKakao(req);

        assertThat(resp.isNewUser()).isTrue();
        verify(groupService).createPersonalGroup(USER_ID);
    }

    @Test
    @DisplayName("loginWithKakao — info.id null이면 KAKAO_AUTH_FAILED")
    void loginWithKakao_nullId_throws() {
        KakaoLoginRequest req = new KakaoLoginRequest("bad-code", "https://redirect.uri");
        KakaoTokenResponse token = new KakaoTokenResponse("k-access", "Bearer", null, 3600, null);
        KakaoUserInfo info = new KakaoUserInfo(null, null); // id=null

        when(kakaoOAuthClient.exchangeToken("bad-code", "https://redirect.uri")).thenReturn(token);
        when(kakaoOAuthClient.fetchUserInfo("k-access")).thenReturn(info);

        assertThatThrownBy(() -> authService.loginWithKakao(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
    }

    @Test
    @DisplayName("loginWithKakao — info null이면 KAKAO_AUTH_FAILED")
    void loginWithKakao_nullInfo_throws() {
        KakaoLoginRequest req = new KakaoLoginRequest("bad-code2", "https://redirect.uri");
        KakaoTokenResponse token = new KakaoTokenResponse("k-access2", "Bearer", null, 3600, null);

        when(kakaoOAuthClient.exchangeToken("bad-code2", "https://redirect.uri")).thenReturn(token);
        when(kakaoOAuthClient.fetchUserInfo("k-access2")).thenReturn(null);

        assertThatThrownBy(() -> authService.loginWithKakao(req))
                .isInstanceOf(CustomException.class)
                .extracting(AuthServiceTest::codeOf)
                .isEqualTo(ErrorCode.KAKAO_AUTH_FAILED);
    }

    @Test
    @DisplayName("loginWithKakao — 신규 가입 시 이메일 충돌이면 email=null 로 저장")
    void loginWithKakao_emailConflict_saveNullEmail() {
        KakaoLoginRequest req = new KakaoLoginRequest("code-conflict", "https://redirect.uri");
        KakaoTokenResponse token = new KakaoTokenResponse("k-access3", "Bearer", null, 3600, null);
        KakaoUserInfo.KakaoAccount account = new KakaoUserInfo.KakaoAccount("conflict@example.com", false, null);
        KakaoUserInfo info = new KakaoUserInfo(88L, account);

        User saved = User.builder()
                .email(null).nickname("사용자 1234").profileImageUrl("mallang:sad")
                .oauthProvider(OauthProvider.KAKAO).oauthId("88").build();
        ReflectionTestUtils.setField(saved, "id", USER_ID);

        when(kakaoOAuthClient.exchangeToken("code-conflict", "https://redirect.uri")).thenReturn(token);
        when(kakaoOAuthClient.fetchUserInfo("k-access3")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.KAKAO, "88"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("conflict@example.com")).thenReturn(true); // 충돌
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // 이메일이 null 로 저장되는지 검증
            assertThat(u.getEmail()).isNull();
            return saved;
        });
        when(jwtUtils.generateAccessToken(anyLong(), any())).thenReturn("access");
        when(jwtUtils.generateRefreshToken(anyLong())).thenReturn("refresh");

        authService.loginWithKakao(req);

        verify(userRepository).save(any(User.class));
    }

    // ============================================================
    // loginWithApple
    // ============================================================

    @Test
    @DisplayName("loginWithApple — 기존 사용자: 토큰 발급 + isNewUser=false")
    void loginWithApple_existingUser() {
        AppleLoginRequest req = new AppleLoginRequest("apple-identity-token", null);
        AppleUserInfo info = new AppleUserInfo("apple-sub-123", "apple@example.com", true);
        User user = oauthUser(OauthProvider.APPLE, "apple-sub-123");

        when(appleOAuthClient.verifyIdentityToken("apple-identity-token")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.APPLE, "apple-sub-123"))
                .thenReturn(Optional.of(user));
        when(jwtUtils.generateAccessToken(USER_ID, "oauth@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithApple(req);

        assertThat(resp.isNewUser()).isFalse();
        verify(groupService, never()).createPersonalGroup(anyLong());
    }

    @Test
    @DisplayName("loginWithApple — 신규 사용자 + fullName 있음: 닉네임으로 사용 + 개인그룹 생성")
    void loginWithApple_newUser_withFullName() {
        AppleLoginRequest req = new AppleLoginRequest("apple-token-new", "홍길동");
        AppleUserInfo info = new AppleUserInfo("apple-sub-new", "newaple@example.com", true);

        User saved = oauthUser(OauthProvider.APPLE, "apple-sub-new");
        when(appleOAuthClient.verifyIdentityToken("apple-token-new")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.APPLE, "apple-sub-new"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("newaple@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getNickname()).isEqualTo("홍길동");
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(jwtUtils.generateAccessToken(USER_ID, null)).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithApple(req);

        assertThat(resp.isNewUser()).isTrue();
        verify(groupService).createPersonalGroup(USER_ID);
    }

    @Test
    @DisplayName("loginWithApple — 신규 사용자 + fullName null: fallback 닉네임 사용")
    void loginWithApple_newUser_noFullName() {
        AppleLoginRequest req = new AppleLoginRequest("apple-token-nofullname", null);
        AppleUserInfo info = new AppleUserInfo("apple-sub-nofull", null, null);

        when(appleOAuthClient.verifyIdentityToken("apple-token-nofullname")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.APPLE, "apple-sub-nofull"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            // fallback 닉네임은 "사용자 NNNN" 패턴
            assertThat(u.getNickname()).startsWith("사용자 ");
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(jwtUtils.generateAccessToken(anyLong(), any())).thenReturn("access");
        when(jwtUtils.generateRefreshToken(anyLong())).thenReturn("refresh");

        authService.loginWithApple(req);

        verify(userRepository).save(any(User.class));
    }

    // ============================================================
    // loginWithGoogle
    // ============================================================

    @Test
    @DisplayName("loginWithGoogle — 기존 사용자: 토큰 발급 + isNewUser=false")
    void loginWithGoogle_existingUser() {
        GoogleLoginRequest req = new GoogleLoginRequest("google-id-token");
        GoogleUserInfo info = new GoogleUserInfo("google-sub-1", "google@example.com", true, "구글유저", null);
        User user = oauthUser(OauthProvider.GOOGLE, "google-sub-1");

        when(googleOAuthClient.verifyIdToken("google-id-token")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.GOOGLE, "google-sub-1"))
                .thenReturn(Optional.of(user));
        when(jwtUtils.generateAccessToken(USER_ID, "oauth@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithGoogle(req);

        assertThat(resp.isNewUser()).isFalse();
        verify(groupService, never()).createPersonalGroup(anyLong());
    }

    @Test
    @DisplayName("loginWithGoogle — 신규 사용자: 자동 가입 + 개인그룹 생성 + isNewUser=true")
    void loginWithGoogle_newUser() {
        GoogleLoginRequest req = new GoogleLoginRequest("google-id-token-new");
        GoogleUserInfo info = new GoogleUserInfo("google-sub-new", "newgoogle@example.com", true, "구글신규", null);

        when(googleOAuthClient.verifyIdToken("google-id-token-new")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.GOOGLE, "google-sub-new"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("newgoogle@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getNickname()).isEqualTo("구글신규");
            assertThat(u.getOauthProvider()).isEqualTo(OauthProvider.GOOGLE);
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(jwtUtils.generateAccessToken(USER_ID, "newgoogle@example.com")).thenReturn("access");
        when(jwtUtils.generateRefreshToken(USER_ID)).thenReturn("refresh");

        AuthResponse resp = authService.loginWithGoogle(req);

        assertThat(resp.isNewUser()).isTrue();
        verify(groupService).createPersonalGroup(USER_ID);
    }

    @Test
    @DisplayName("loginWithGoogle — 신규 가입 시 Google name null이면 fallback 닉네임 사용")
    void loginWithGoogle_newUser_nullName_fallbackNickname() {
        GoogleLoginRequest req = new GoogleLoginRequest("google-noname-token");
        GoogleUserInfo info = new GoogleUserInfo("google-sub-noname", null, null, null, null);

        when(googleOAuthClient.verifyIdToken("google-noname-token")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.GOOGLE, "google-sub-noname"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getNickname()).startsWith("사용자 ");
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(jwtUtils.generateAccessToken(anyLong(), any())).thenReturn("access");
        when(jwtUtils.generateRefreshToken(anyLong())).thenReturn("refresh");

        authService.loginWithGoogle(req);

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("loginWithGoogle — 신규 가입 시 이메일 충돌이면 email=null 로 저장")
    void loginWithGoogle_newUser_emailConflict_savesNullEmail() {
        GoogleLoginRequest req = new GoogleLoginRequest("google-conflict-token");
        GoogleUserInfo info = new GoogleUserInfo("google-sub-conflict", "used@example.com", true, "구글유저", null);

        when(googleOAuthClient.verifyIdToken("google-conflict-token")).thenReturn(info);
        when(userRepository.findByOauthProviderAndOauthId(OauthProvider.GOOGLE, "google-sub-conflict"))
                .thenReturn(Optional.empty());
        when(userRepository.existsByEmail("used@example.com")).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getEmail()).isNull();
            ReflectionTestUtils.setField(u, "id", USER_ID);
            return u;
        });
        when(jwtUtils.generateAccessToken(anyLong(), any())).thenReturn("access");
        when(jwtUtils.generateRefreshToken(anyLong())).thenReturn("refresh");

        authService.loginWithGoogle(req);

        verify(userRepository).save(any(User.class));
    }
}

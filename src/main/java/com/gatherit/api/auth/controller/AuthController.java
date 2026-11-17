package com.gatherit.api.auth.controller;

import com.gatherit.api.auth.dto.AppleLoginRequest;
import com.gatherit.api.auth.dto.AuthResponse;
import com.gatherit.api.auth.dto.GoogleLoginRequest;
import com.gatherit.api.auth.dto.KakaoLoginRequest;
import com.gatherit.api.auth.dto.LoginRequest;
import com.gatherit.api.auth.dto.LogoutRequest;
import com.gatherit.api.auth.dto.RefreshRequest;
import com.gatherit.api.auth.dto.SignupRequest;
import com.gatherit.api.auth.service.AuthService;
import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "회원가입 / 로그인 / 토큰 회전")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public ApiResponse<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        return ApiResponse.ok(authService.signup(req));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @Operation(summary = "토큰 회전 (refresh) — 세션 바인딩 검증")
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req));
    }

    @Operation(summary = "카카오 로그인 — FE 의 인가코드로 카카오 토큰 교환 후 우리 JWT 발급")
    @PostMapping("/oauth/kakao")
    public ApiResponse<AuthResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest req) {
        return ApiResponse.ok(authService.loginWithKakao(req));
    }

    /**
     * 모바일 앱용 카카오 OAuth callback 중계.
     * 카카오는 custom scheme(gatherit://) redirect URI 를 받지 않으므로,
     * 카카오 → 이 endpoint(https) → 302 → gatherit:// deep link 로 중계.
     * SFAuthenticationSession 이 deep link 를 캡쳐해서 앱에 code 전달.
     */
    @Operation(summary = "카카오 모바일 OAuth callback 중계 — 앱 deep link 로 302 redirect")
    @GetMapping("/kakao/redirect-mobile")
    public ResponseEntity<Void> kakaoMobileRedirect(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error
    ) {
        StringBuilder url = new StringBuilder("gatherit://oauth/kakao/callback?");
        if (code != null && !code.isBlank()) {
            url.append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        }
        if (error != null && !error.isBlank()) {
            if (url.charAt(url.length() - 1) != '?') url.append('&');
            url.append("error=").append(URLEncoder.encode(error, StandardCharsets.UTF_8));
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url.toString())).build();
    }

    @Operation(summary = "Apple 로그인 — 앱의 identityToken 검증 후 우리 JWT 발급")
    @PostMapping("/oauth/apple")
    public ApiResponse<AuthResponse> appleLogin(@Valid @RequestBody AppleLoginRequest req) {
        return ApiResponse.ok(authService.loginWithApple(req));
    }

    @Operation(summary = "Google 로그인 — 앱의 idToken 검증 후 우리 JWT 발급")
    @PostMapping("/oauth/google")
    public ApiResponse<AuthResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest req) {
        return ApiResponse.ok(authService.loginWithGoogle(req));
    }

    @Operation(summary = "로그아웃 — refresh token 무효화")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal UserDetailsImpl me,
                                    @Valid @RequestBody(required = false) LogoutRequest request) {
        authService.logout(me.getUserId(), request);
        return ApiResponse.ok();
    }
}

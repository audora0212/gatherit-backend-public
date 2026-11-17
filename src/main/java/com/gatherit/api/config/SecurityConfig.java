package com.gatherit.api.config;

import com.gatherit.api.common.ratelimit.RateLimitFilter;
import com.gatherit.api.security.JwtAuthenticationEntryPoint;
import com.gatherit.api.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final RateLimitFilter rateLimitFilter;

    @Value("${gatherit.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // public endpoints
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/oauth/kakao", "/api/auth/oauth/apple", "/api/auth/oauth/google").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/kakao/redirect-mobile").permitAll()
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        // iCal 구독 피드 — 구글/애플 캘린더가 토큰으로 접근 (JWT 못 붙임)
                        .requestMatchers(HttpMethod.GET, "/api/ical/**").permitAll()
                        // 앱 버전/업데이트 안내 — 콜드스타트(미인증/로그인 전)에서도 조회
                        .requestMatchers(HttpMethod.GET, "/api/app/version").permitAll()
                        // 카카오톡 공유 링크에서 앱 설치 없이 참여하는 공개 투표
                        .requestMatchers("/api/public/polls/**").permitAll()
                        // 그 외 인증 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    /**
     * CORS — 로컬 개발 시 Expo Web (8081/19006), Expo Go (LAN IP) 둘 다 허용.
     * 운영 단계에선 application-prod.yml 의 gatherit.cors.allowed-origins 로 좁히기.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        if ("*".equals(allowedOrigins)) {
            cfg.setAllowedOriginPatterns(List.of("*"));
        } else {
            cfg.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        }
        cfg.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}

package com.gatherit.api.user.entity;

import com.gatherit.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_users_username", columnNames = "username"),
        @UniqueConstraint(name = "uk_users_oauth", columnNames = {"oauth_provider", "oauth_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** OAuth 사용자는 이메일 동의 안 받았을 수 있어 nullable. NULL 다중 허용 (MySQL InnoDB 기본). */
    @Column(name = "email", length = 200)
    private String email;

    /** 로그인용 ID — local 가입자만 사용. OAuth 사용자는 null. unique. */
    @Column(name = "username", length = 50)
    private String username;

    /** OAuth 사용자는 비밀번호 없음 — nullable. 로컬 가입 사용자만 BCrypt 해시 저장. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /** 가입 출처. LOCAL = 이메일/비밀번호, KAKAO/GOOGLE = OAuth. */
    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20)
    private OauthProvider oauthProvider;

    /** OAuth provider 가 발급한 사용자 ID. (oauth_provider, oauth_id) 조합으로 unique. */
    @Column(name = "oauth_id", length = 100)
    private String oauthId;

    /**
     * 기존 계정과의 호환을 위한 역할 값. 신규 가입자는 USER로 시작한다.
     * columnDefinition 으로 VARCHAR 강제 — Hibernate 가 MySQL native ENUM 만들면
     * ALTER TABLE 시 default 가 첫 enum 값(알파벳 순 ADMIN) 으로 잡혀버려 위험.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, columnDefinition = "varchar(20) not null default 'USER'")
    private UserRole role = UserRole.USER;

    /**
     * Expo Push Service token — `ExponentPushToken[xxx]` 형식.
     * 앱이 권한 허락 후 등록. 디바이스 변경 / 권한 회수 시 갱신 또는 null.
     */
    @Column(name = "expo_push_token", length = 200)
    private String expoPushToken;

    /**
     * 푸시 토큰 플랫폼 — "ios" | "android" | null. 토큰 등록 시 X-App-Version 헤더에서 추출.
     * 전송 분기: iOS=APNs(직접 HTTP/2), android=FCM(HTTP v1). null(레거시/웹)=APNs 취급.
     */
    @Column(name = "push_platform", length = 10)
    private String pushPlatform;

    /**
     * 생성자 식별 바 색 — 본인이 고른 hex (#RRGGBB). 캘린더/일정에서 "내가 만든 일정" 표시용.
     * null = 클라이언트가 userId 해시로 자동색 fallback.
     */
    @Column(name = "personal_color", length = 7)
    private String personalColor;

    /**
     * iCal 구독(webcal) URL 인증용 비밀 토큰. 구글/애플 캘린더가 백그라운드로 .ics 를
     * 가져올 때 JWT 를 못 붙이므로 URL 의 이 토큰으로 본인 식별. 유출 대비 재발급(rotate) 가능.
     */
    @Column(name = "ical_token", length = 64)
    private String icalToken;

    @Builder
    private User(String email, String username, String passwordHash, String nickname, String profileImageUrl,
                 OauthProvider oauthProvider, String oauthId, UserRole role) {
        this.email = email;
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.oauthProvider = oauthProvider;
        this.oauthId = oauthId;
        this.role = role != null ? role : UserRole.USER;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileImage(String url) {
        this.profileImageUrl = url;
    }

    public void updateExpoPushToken(String token) {
        this.expoPushToken = token;
    }

    /** 푸시 토큰 플랫폼 갱신 — "ios"|"android"|null. 토큰 등록 시 함께 호출. */
    public void updatePushPlatform(String platform) {
        this.pushPlatform = platform;
    }

    public void updatePersonalColor(String color) {
        this.personalColor = color;
    }

    public void updateIcalToken(String token) {
        this.icalToken = token;
    }
}

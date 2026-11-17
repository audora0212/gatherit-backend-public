package com.gatherit.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 /v2/user/me 응답.
 * 동의 항목 미동의 시 kakao_account/profile 의 일부 필드는 null.
 * https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api#req-user-info-response
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfo(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(
            String email,
            @JsonProperty("email_needs_agreement") Boolean emailNeedsAgreement,
            Profile profile
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            String nickname,
            @JsonProperty("profile_image_url") String profileImageUrl,
            @JsonProperty("thumbnail_image_url") String thumbnailImageUrl
    ) {}
}

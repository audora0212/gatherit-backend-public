package com.gatherit.api.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 /oauth/token 응답.
 * https://developers.kakao.com/docs/latest/ko/kakaologin/rest-api#request-token-response
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Integer expiresIn,
        String scope
) {}

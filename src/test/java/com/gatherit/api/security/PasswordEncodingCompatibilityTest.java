package com.gatherit.api.security;

import com.gatherit.api.common.ratelimit.RateLimitFilter;
import com.gatherit.api.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class PasswordEncodingCompatibilityTest {
    private final PasswordEncoder encoder = new SecurityConfig(
            mock(JwtAuthenticationFilter.class), mock(JwtAuthenticationEntryPoint.class),
            mock(RateLimitFilter.class)).passwordEncoder();

    // Public dummy fixtures generated with spring-security-crypto 6.3.4, never real accounts.
    static Stream<Arguments> legacyLongPasswords() {
        return Stream.of(
                Arguments.of("ASCII 73 bytes", "b".repeat(73), "$2a$04$yRTkQaioAiYow4gIvrnaMu1bM28xC7IKNMgfKDv/jyJnh.IIXnHYq"),
                Arguments.of("ASCII 100 bytes", "c".repeat(100), "$2a$04$394N2kF060nf3oU3YS5VFuUbUGV3iRo9/OCbg7WjS3Y7XL1Igw.D."),
                Arguments.of("Korean 75 bytes", "\uB098".repeat(25), "$2a$04$cR2LlOwrJw91cUM2rfgcDOICrbMa971qWMFdl/dqFfu0CsKnr3dti"),
                Arguments.of("Emoji 76 bytes", "\uD83D\uDE00".repeat(19), "$2a$04$HMfu2fEYXg1th8sfKwbqae1GuAUYF5v3Tjm7yvQGcOoXHFNYa3Tdy"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyLongPasswords")
    void legacy634LongHashesRemainVerifiableWithoutRehashing(String label, String password, String hash) {
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches("different-prefix", hash)).isFalse();
        // Upstream intentionally preserves legacy truncation on matches; do not claim it fixed.
        assertThat(encoder.matches(password + "ignored-legacy-suffix", hash)).isTrue();
    }

    static Stream<Arguments> supportedPasswords() {
        return Stream.of(Arguments.of("ASCII 72 bytes", "a".repeat(72)),
                Arguments.of("Korean 72 bytes", "\uAC00".repeat(24)),
                Arguments.of("Emoji 72 bytes", "\uD83D\uDE00".repeat(18)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedPasswords")
    void new72BytePasswordsCanStillBeEncoded(String label, String password) {
        assertThat(encoder.matches(password, encoder.encode(password))).isTrue();
    }

    static Stream<Arguments> unsupportedNewPasswords() {
        return Stream.of(Arguments.of("ASCII 73 bytes", "a".repeat(73)),
                Arguments.of("ASCII 100 bytes", "a".repeat(100)),
                Arguments.of("Korean 75 bytes", "\uAC00".repeat(25)),
                Arguments.of("Emoji 76 bytes", "\uD83D\uDE00".repeat(19)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedNewPasswords")
    void libraryRejectsOnlyNewOverlengthHashes(String label, String password) {
        assertThatIllegalArgumentException().isThrownBy(() -> encoder.encode(password));
    }

    @Test
    void existingNormalHashStillMatches() {
        String hash = "$2a$04$RoFqOWh.R2y2SEb5.mshwOOnhrCJX2XaESsTn6TQOJGuGYvm5teGu";
        assertThat(encoder.matches("existing-password", hash)).isTrue();
        assertThat(encoder.matches("different-password", hash)).isFalse();
    }
}

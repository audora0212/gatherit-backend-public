package com.gatherit.api.security;

import com.gatherit.api.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private static final String SECRET = "filter-test-secret-at-least-32-bytes-long";
    private JwtUtils jwt;
    private JwtAuthenticationFilter filter;
    private UserDetailsServiceImpl users;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 31_536_000_000L);
        jwt.init();
        users = mock(UserDetailsServiceImpl.class);
        User user = User.builder().nickname("test").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        when(users.loadUserById(1L)).thenReturn(new UserDetailsImpl(user));
        filter = new JwtAuthenticationFilter(jwt, users);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessTokenAuthenticatesProtectedRequest() throws Exception {
        assertThat(protectedResponse(jwt.generateAccessToken(1L, null))).isEqualTo(200);
        verify(users).loadUserById(1L);
    }

    @Test
    void refreshTokenNeverAuthenticatesProtectedRequestIncludingAfterLogout() throws Exception {
        // Filter never consults refresh storage: rejection must hold even after logout deletes its row.
        assertThat(protectedResponse(jwt.generateRefreshToken(1L))).isEqualTo(401);
        verifyNoInteractions(users);
    }

    @Test
    void legacyRefreshCannotBypassTheFilterButEmailLessLegacyAccessStillWorks() throws Exception {
        assertThat(protectedResponse(legacyToken(31_536_000_000L))).isEqualTo(401);
        assertThat(protectedResponse(legacyToken(3_600_000L))).isEqualTo(200);
    }

    private int protectedResponse(String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users/me");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(req, response, (request, res) -> {
            response.setStatus(SecurityContextHolder.getContext().getAuthentication() == null ? 401 : 200);
        });
        return response.getStatus();
    }

    private String legacyToken(long lifetime) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject("1").issuedAt(new Date(now)).expiration(new Date(now + lifetime))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}

package com.gatherit.api.auth.service;

import com.gatherit.api.auth.controller.AuthController;
import com.gatherit.api.auth.dto.LogoutRequest;
import com.gatherit.api.security.UserDetailsImpl;
import com.gatherit.api.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LogoutContractTest {
    private AuthService auth;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        User user = User.builder().nickname("test").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        var principal = new UserDetailsImpl(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void oldClientWithNoBodyUsesTheLegacyContract() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
        verify(auth).logout(1L, null);
    }

    @Test
    void newClientSnapshotIsForwardedAndSuccessfulNoOpIsStill200() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"snapshot\"}")).andExpect(status().isOk());
        verify(auth).logout(1L, new LogoutRequest("snapshot", null));
    }

    @Test
    void recoveryProofIsForwardedWithSnapshot() throws Exception {
        String attempt = "a".repeat(64);
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"snapshot\",\"refreshAttemptId\":\"" + attempt + "\"}"))
                .andExpect(status().isOk());
        verify(auth).logout(1L, new LogoutRequest("snapshot", attempt));
    }

    @Test
    void emptyBodyObjectCannotAccidentallyEnterTheLegacyUnboundContract() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    void malformedAttemptIsRejectedBeforeSessionMutation() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"snapshot\",\"refreshAttemptId\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }
}

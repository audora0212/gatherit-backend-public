package com.gatherit.api.auth.service;

import com.gatherit.api.auth.entity.RefreshToken;
import com.gatherit.api.auth.repository.RefreshTokenRepository;
import com.gatherit.api.config.JpaConfig;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.security.JwtAuthenticationFilter;
import com.gatherit.api.security.UserDetailsServiceImpl;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real JPA transactions and row locks against an isolated in-memory H2 database (no Docker/MySQL). */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jwt.secret=integration-test-secret-at-least-32-bytes-long",
        "jwt.expiration=3600000", "jwt.refresh-expiration=31536000000"
})
@ActiveProfiles("test")
@ContextConfiguration(classes = RefreshTokenStoreIntegrationTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenStoreIntegrationTest {
    private static final String SECRET = "integration-test-secret-at-least-32-bytes-long";
    private static final String ATTEMPT = "a".repeat(64);

    @Configuration
    @EntityScan(basePackageClasses = {RefreshToken.class, User.class})
    @EnableJpaRepositories(basePackageClasses = {RefreshTokenRepository.class, UserRepository.class})
    @Import({JpaConfig.class, RefreshTokenStore.class, JwtUtils.class})
    static class Config {}

    @Autowired RefreshTokenStore store;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired JwtUtils jwt;
    @Autowired PlatformTransactionManager transactionManager;
    private Long userId;
    private String first;
    private Instant now;

    @BeforeEach
    void setUp() {
        tokens.deleteAll();
        users.deleteAll();
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        moveClock(now);
        ReflectionTestUtils.setField(store, "refreshRecoveryMs", 604_800_000L);
        userId = users.save(User.builder().nickname("auth-test").build()).getId();
        first = jwt.generateRefreshToken(userId);
        store.save(userId, first);
    }

    @Test
    void lostResponseRecoversAfter61SecondsAndNextDayWithoutExtendingSession() {
        String current = store.refresh(userId, first, ATTEMPT).token();
        RefreshToken saved = row();
        Instant expiry = saved.getExpiresAt();
        Instant recoveryExpiry = saved.getRecoveryExpiresAt();
        assertThat(saved.getRecoveryKeyHash()).doesNotContain(ATTEMPT);
        for (long seconds : new long[]{61, 86_400, 172_800}) {
            moveClock(now.plusSeconds(seconds));
            var result = store.refresh(userId, first, ATTEMPT);
            assertThat(result.token()).isEqualTo(current);
            assertThat(result.outcome()).isEqualTo("attempt_recovered");
            assertThat(row().getExpiresAt()).isEqualTo(expiry);
            assertThat(row().getRecoveryExpiresAt()).isEqualTo(recoveryExpiry);
        }
    }

    @Test
    void tokenAloneOrDifferentAttemptAfterGraceCannotRecoverOrInvalidateCurrentSession() {
        String current = store.refresh(userId, first, ATTEMPT).token();
        moveClock(now.plusSeconds(61));
        assertRejected(first, null);
        assertRejected(first, "b".repeat(64));
        assertThat(row().getToken()).isEqualTo(current);
        assertThat(store.refresh(userId, first, ATTEMPT).token()).isEqualTo(current);
    }

    @Test
    void missingAttemptRetainsExactlyTheLegacyGraceBehavior() {
        String current = store.refresh(userId, first, null).token();
        assertThat(row().getRecoveryKeyHash()).isNull();
        moveClock(now.plusSeconds(59));
        assertThat(store.refresh(userId, first, null).token()).isEqualTo(current);
        moveClock(now.plusSeconds(61));
        assertRejected(first, null);
        assertThat(row().getToken()).isEqualTo(current);
    }

    @Test
    void malformedAttemptNeverMutatesCurrentState() {
        for (String malformed : List.of("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            assertRejected(first, malformed);
            assertThat(row().getToken()).isEqualTo(first);
        }
    }

    @Test
    void recoveryStopsAtSevenDaysEvenWhenConfigurationAsksForLonger() {
        ReflectionTestUtils.setField(store, "refreshRecoveryMs", 31_536_000_000L);
        store.refresh(userId, first, ATTEMPT);
        moveClock(now.plusSeconds(7 * 86_400L - 1));
        assertThat(store.refresh(userId, first, ATTEMPT).outcome()).isEqualTo("attempt_recovered");
        moveClock(now.plusSeconds(7 * 86_400L));
        assertRejected(first, ATTEMPT);
    }

    @Test
    void recoveryCannotOutliveTheOriginalJwt() {
        String shortLived = legacyRefresh(now.minusSeconds(86_400), now.plusSeconds(120));
        store.save(userId, shortLived);
        store.refresh(userId, shortLived, ATTEMPT);
        assertThat(row().getRecoveryExpiresAt()).isEqualTo(jwt.getExpiration(shortLived));
        moveClock(now.plusSeconds(121));
        assertRejected(shortLived, ATTEMPT);
    }

    @Test
    void recoveryRequiresUnexpiredCurrentSessionAsWell() {
        store.refresh(userId, first, ATTEMPT);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ReflectionTestUtils.setField(tokens.findById(userId).orElseThrow(), "expiresAt", now.plusSeconds(30));
        });
        moveClock(now.plusSeconds(31));
        assertRejected(first, ATTEMPT);
    }

    @Test
    void nextRotationDiscardsPriorRecoveryKey() {
        String second = store.refresh(userId, first, ATTEMPT).token();
        String third = store.refresh(userId, second, "b".repeat(64)).token();
        assertRejected(first, ATTEMPT);
        assertThat(row().getToken()).isEqualTo(third);
        moveClock(now.plusSeconds(61));
        assertThat(store.refresh(userId, second, "b".repeat(64)).token()).isEqualTo(third);
    }

    @Test
    void newLoginDiscardsPriorSessionIncludingItsGraceAndAttemptProof() {
        String second = store.refresh(userId, first, ATTEMPT).token();
        String newLogin = jwt.generateRefreshToken(userId);
        store.save(userId, newLogin);
        assertRejected(first, ATTEMPT);
        assertRejected(second, null);
        assertThat(row().getPreviousToken()).isNull();
        assertThat(row().getRecoveryKeyHash()).isNull();
        assertThat(row().getToken()).isEqualTo(newLogin);
    }

    @Test
    void logoutPersistsDeletionAndNeitherCurrentNorOldTokenCanRestoreSession() {
        String second = store.refresh(userId, first, ATTEMPT).token();
        store.invalidate(userId);
        assertThat(tokens.findById(userId)).isEmpty();
        assertRejected(first, ATTEMPT);
        assertRejected(second, null);
        assertThat(tokens.findById(userId)).isEmpty();
    }

    @Test
    void loggedOutRefreshTokenCannotBeUsedAsBearerOnProtectedApi() throws Exception {
        store.invalidate(userId);
        assertThat(tokens.findById(userId)).isEmpty();
        var details = mock(UserDetailsServiceImpl.class);
        var filter = new JwtAuthenticationFilter(jwt, details);
        var request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + first);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        try {
            filter.doFilter(request, response, (req, res) -> response.setStatus(
                    SecurityContextHolder.getContext().getAuthentication() == null ? 401 : 200));
            assertThat(response.getStatus()).isEqualTo(401);
            verifyNoInteractions(details);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void legacySessionCanRotateIntoTypedTokensWithoutLoggingInAgain() {
        String legacy = legacyRefresh(now.minusSeconds(60), now.plusSeconds(86_400));
        store.save(userId, legacy);
        assertThat(jwt.validateRefreshToken(legacy)).isTrue();
        String next = store.refresh(userId, legacy, ATTEMPT).token();
        assertThat(jwt.validateRefreshToken(next)).isTrue();
        assertThatThrownBy(() -> jwt.validateAccessToken(next)).isInstanceOf(CustomException.class);
    }

    @Test
    void concurrentRefreshesRotateOnlyOnceAndReturnTheSameCurrentToken() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RefreshTokenStore.RotationResult>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return store.refresh(userId, first, ATTEMPT);
                }));
            }
            start.countDown();
            List<RefreshTokenStore.RotationResult> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(RefreshTokenStore.RotationResult::token).distinct()).hasSize(1);
            assertThat(results.stream().filter(r -> r.outcome().equals("rotated"))).hasSize(1);
            assertThat(row().getPreviousToken()).isEqualTo(first);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousLogoutAndRefreshCannotResurrectSession() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> refresh = executor.submit(() -> {
                await(start);
                try { store.refresh(userId, first, ATTEMPT); }
                catch (CustomException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN); }
            });
            Future<?> logout = executor.submit(() -> { await(start); store.invalidate(userId); });
            start.countDown();
            refresh.get(10, TimeUnit.SECONDS);
            logout.get(10, TimeUnit.SECONDS);
            assertThat(tokens.findById(userId)).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousLoginAndOldRefreshLeavesOnlyTheNewLoginSession() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        String newLogin = jwt.generateRefreshToken(userId);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> refresh = executor.submit(() -> {
                await(start);
                try { store.refresh(userId, first, ATTEMPT); }
                catch (CustomException e) { assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN); }
            });
            Future<?> login = executor.submit(() -> { await(start); store.save(userId, newLogin); });
            start.countDown();
            refresh.get(10, TimeUnit.SECONDS);
            login.get(10, TimeUnit.SECONDS);
            assertThat(row().getToken()).isEqualTo(newLogin);
            assertThat(row().getRecoveryKeyHash()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delayedBoundLogoutCannotDeleteANewerLoginSession() {
        String newerLogin = jwt.generateRefreshToken(userId);
        store.save(userId, newerLogin);
        assertThat(row().getToken()).isEqualTo(newerLogin);
        assertThat(store.invalidateMatching(userId, first, null)).isFalse();
        assertThat(row().getToken()).isEqualTo(newerLogin);
    }

    @Test
    void boundLogoutDeletesItsCurrentSession() {
        assertThat(store.invalidateMatching(userId, first, null)).isTrue();
        assertThat(tokens.findById(userId)).isEmpty();
        assertThat(store.invalidateMatching(userId, first, null)).isFalse();
    }

    @Test
    void boundLogoutStillWorksAfterItsAccessRefreshRotatesTheSnapshotIntoGrace() {
        store.refresh(userId, first, null);
        assertThat(store.invalidateMatching(userId, first, null)).isTrue();
        assertThat(tokens.findById(userId)).isEmpty();
    }

    @Test
    void boundLogoutCanUseItsOriginalRecoveryProofAfterGrace() {
        String current = store.refresh(userId, first, ATTEMPT).token();
        moveClock(now.plusSeconds(61));
        assertThat(store.invalidateMatching(userId, first, "b".repeat(64))).isFalse();
        assertThat(store.invalidateMatching(userId, first, null)).isFalse();
        assertThat(row().getToken()).isEqualTo(current);
        assertThat(store.invalidateMatching(userId, first, ATTEMPT)).isTrue();
        assertThat(tokens.findById(userId)).isEmpty();
    }

    @Test
    void boundLogoutCannotCrossAccountsOrUseMalformedProof() {
        String otherUserToken = jwt.generateRefreshToken(userId + 1);
        assertThat(store.invalidateMatching(userId, otherUserToken, null)).isFalse();
        assertThat(store.invalidateMatching(userId, first, "malformed")).isFalse();
        assertThat(store.invalidateMatching(userId, null, null)).isFalse();
        assertThat(row().getToken()).isEqualTo(first);
    }

    @Test
    void simultaneousNewLoginAndBoundOldLogoutAlwaysKeepsTheNewSession() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        String newLogin = jwt.generateRefreshToken(userId);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> logout = executor.submit(() -> { await(start); store.invalidateMatching(userId, first, null); });
            Future<?> login = executor.submit(() -> { await(start); store.save(userId, newLogin); });
            start.countDown();
            logout.get(10, TimeUnit.SECONDS);
            login.get(10, TimeUnit.SECONDS);
            assertThat(row().getToken()).isEqualTo(newLogin);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oldBinaryChangingPreviousTokenCannotReuseAStoredRecoveryHash() {
        String second = store.refresh(userId, first, ATTEMPT).token();
        String third = jwt.generateRefreshToken(userId);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            RefreshToken rt = tokens.findById(userId).orElseThrow();
            // Model an old JAR: it updates previous/current but does not know recovery columns.
            ReflectionTestUtils.setField(rt, "previousToken", second);
            ReflectionTestUtils.setField(rt, "token", third);
        });
        moveClock(now.plusSeconds(61));
        assertRejected(second, ATTEMPT);
        assertRejected(first, ATTEMPT);
        assertThat(row().getToken()).isEqualTo(third);
    }

    @Test
    void simultaneousLoginsWithNoRefreshRowCreateOneSessionWithoutDuplicateKeyErrors() throws Exception {
        store.invalidate(userId);
        var executor = Executors.newFixedThreadPool(2);
        String other = jwt.generateRefreshToken(userId);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> one = executor.submit(() -> { await(start); store.save(userId, first); });
            Future<?> two = executor.submit(() -> { await(start); store.save(userId, other); });
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
            assertThat(tokens.count()).isEqualTo(1);
            assertThat(row().getToken()).isIn(first, other);
            assertThat(row().getPreviousToken()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRejected(String token, String attempt) {
        assertThatThrownBy(() -> store.refresh(userId, token, attempt)).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    private RefreshToken row() { return tokens.findById(userId).orElseThrow(); }
    private void moveClock(Instant instant) {
        ReflectionTestUtils.setField(store, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }
    private String legacyRefresh(Instant issued, Instant expiry) {
        return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(issued)).expiration(Date.from(expiry))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}

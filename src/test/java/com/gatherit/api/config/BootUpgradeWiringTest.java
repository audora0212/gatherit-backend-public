package com.gatherit.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.GatherItApplication;
import com.gatherit.api.auth.service.AppleOAuthClient;
import com.gatherit.api.auth.service.GoogleOAuthClient;
import com.gatherit.api.auth.service.KakaoOAuthClient;
import com.gatherit.api.common.notify.SlackNotifier;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.notification.service.ApnsService;
import com.gatherit.api.notification.service.ExpoPushService;
import com.gatherit.api.notification.service.FcmService;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.user.OAuthAvatarMigration;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Full application wiring against an ephemeral H2 database, never local/prod configuration.
 * Business services, repositories, MVC and JWT security are real. Only startup/background
 * effects and external providers are replaced; this is not live OAuth, S3 or MySQL QA.
 */
@SpringBootTest(classes = GatherItApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=127.0.0.1",
                "spring.datasource.url=jdbc:h2:mem:boot-upgrade-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa", "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Seoul",
                "spring.jpa.show-sql=false", "spring.jpa.properties.hibernate.format_sql=false",
                "jwt.secret=boot-upgrade-contract-only-key-at-least-32-bytes",
                "jwt.expiration=3600000", "jwt.refresh-expiration=31536000000",
                "gatherit.storage.type=s3", "aws.s3.bucket=boot-upgrade-fixture-only",
                "aws.s3.access-key=", "aws.s3.secret-key=", "aws.s3.endpoint=",
                "slack.webhook-url=", "kakao.client-id=fixture-only", "kakao.client-secret=",
                "google.client-ids=", "fcm.credentials-path=", "apns.key-path=",
                "ai.gemini.api-key=", "logging.level.org.hibernate.SQL=OFF"
        })
@ActiveProfiles("boot-upgrade-contract")
class BootUpgradeWiringTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;

    // @EnableScheduling registers jobs, but this mock never executes a runnable.
    @MockitoBean(name = "taskScheduler") TaskScheduler taskScheduler;
    @MockitoBean OAuthAvatarMigration avatarMigration;
    @MockitoBean FcmService fcm;
    @MockitoBean ApnsService apns;
    @MockitoBean ExpoPushService expoPush;
    @MockitoBean S3Client s3;
    @MockitoBean S3Presigner s3Presigner;
    @MockitoBean PhotoStorage photoStorage;
    @MockitoBean SlackNotifier slack;
    @MockitoBean AppleOAuthClient apple;
    @MockitoBean GoogleOAuthClient google;
    @MockitoBean KakaoOAuthClient kakao;

    @Test
    void isolatedDatabaseAndActualHealthEndpointAreReady() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:boot-upgrade-contract");
        }
        assertThat(mockingDetails(taskScheduler).isMock()).isTrue();
        var response = request("GET", "/actuator/health", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("status").asText()).isEqualTo("UP");
        verifyNoInteractions(fcm, apns, expoPush, s3, s3Presigner, photoStorage, apple, google, kakao);
    }

    @Test
    void protectedApiStillReturnsTheExistingUnauthorizedEnvelope() throws Exception {
        var response = request("GET", "/api/me", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    void openApiDescribesProductRoutes() throws Exception {
        var response = request("GET", "/api-docs", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = mapper.readTree(response.body());
        assertThat(document.path("openapi").asText()).startsWith("3.");
        JsonNode paths = document.path("paths");
        assertThat(paths.path("/api/auth/login").has("post")).isTrue();
        assertThat(paths.path("/api/announcements").has("get")).isTrue();
        assertThat(paths.path("/api/popups/active").has("get")).isTrue();
        assertThat(paths.path("/api/popups/archive").has("get")).isTrue();
    }

    @Test
    void realSignupLoginRefreshAndAuthenticatedProfileKeepTheirContracts() throws Exception {
        String username = "boot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String password = "fixture-password-42!";
        var signup = request("POST", "/api/auth/signup", Map.of(
                "username", username, "email", username + "@example.invalid",
                "password", password, "nickname", "보안 회귀 확인"), null);
        assertThat(signup.statusCode()).isEqualTo(200);
        JsonNode signupData = successfulData(signup);
        long userId = signupData.path("user").path("id").asLong();
        assertThat(userId).isPositive();
        assertThat(signupData.path("isNewUser").asBoolean()).isTrue();
        assertThat(users.findByUsername(username)).isPresent();

        var login = request("POST", "/api/auth/login", Map.of(
                "username", username, "password", password), null);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode loginData = successfulData(login);
        assertThat(loginData.path("isNewUser").asBoolean()).isFalse();
        assertThat(loginData.path("user").path("id").asLong()).isEqualTo(userId);
        String refreshToken = loginData.path("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();

        var refresh = request("POST", "/api/auth/refresh", Map.of(
                "refreshToken", refreshToken, "refreshAttemptId", "a".repeat(64)), null);
        assertThat(refresh.statusCode()).isEqualTo(200);
        JsonNode refreshed = successfulData(refresh);
        assertThat(refreshed.path("user").path("id").asLong()).isEqualTo(userId);
        assertThat(refreshed.path("refreshToken").asText()).isNotBlank();
        String accessToken = refreshed.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        var profile = request("GET", "/api/me", null, accessToken);
        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(successfulData(profile).path("id").asLong()).isEqualTo(userId);
        assertThat(successfulData(request("GET", "/api/announcements/active", null, accessToken)).isArray())
                .isTrue();
        assertThat(successfulData(request("GET", "/api/announcements", null, accessToken)).isArray()).isTrue();
        assertThat(successfulData(request("GET", "/api/popups/active", null, accessToken)).isArray()).isTrue();
        assertThat(successfulData(request("GET", "/api/popups/archive", null, accessToken)).isArray()).isTrue();
    }

    private JsonNode successfulData(HttpResponse<String> response) throws Exception {
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        return body.path("data");
    }

    private HttpResponse<String> request(String method, String path, Object body, String accessToken)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

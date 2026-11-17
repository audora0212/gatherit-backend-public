package com.gatherit.api.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.auth.dto.AuthResponse;
import com.gatherit.api.common.dto.ApiResponse;
import com.gatherit.api.event.dto.EventCreateRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the real MVC converter selected by application.yml, without DB or external services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = HttpJsonCompatibilityTest.TestApplication.class,
        properties = {"spring.profiles.active=http-json-contract", "server.address=127.0.0.1",
                "spring.jpa.hibernate.ddl-auto=none"})
class HttpJsonCompatibilityTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @LocalServerPort int port;
    @Autowired RequestMappingHandlerAdapter adapter;
    @Autowired ObjectMapper mapper;

    @Test
    void mvcUsesTheOfficialJackson2MigrationConverter() {
        var selected = adapter.getMessageConverters().stream()
                .filter(converter -> converter.canWrite(ApiResponse.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertThat(selected).isInstanceOf(MappingJackson2HttpMessageConverter.class);
        assertThat(((MappingJackson2HttpMessageConverter) selected).getObjectMapper()).isSameAs(mapper);
    }

    @Test
    void authResponseKeepsTokenAndNewUserFieldNames() throws Exception {
        var response = send("/json-contract/auth", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = mapper.readTree(response.body());
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("accessToken").asText()).isEqualTo("fixture-access");
        assertThat(root.path("data").path("refreshToken").asText()).isEqualTo("fixture-refresh");
        assertThat(root.path("data").has("isNewUser")).isTrue();
        assertThat(root.path("data").path("isNewUser").asBoolean()).isFalse();
        assertThat(root.path("data").has("newUser")).isFalse();
        assertThat(root.has("code")).isFalse();
        assertThat(root.has("message")).isFalse();
    }

    @Test
    void eventHttpRequestKeepsOffsetDatesAndIgnoresFutureFields() throws Exception {
        var response = send("/json-contract/event", """
                {"title":"모임 ☕", "startAt":"2026-11-02T09:30:00.123+09:00",
                 "endAt":"2026-11-02T10:30:00+09:00", "groupIds":[21,22],
                 "colorKey":"team", "allDay":false, "futureClientField":{"ignored":true}}
                """);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("title").asText()).isEqualTo("모임 ☕");
        assertThat(data.path("startAt").asText()).isEqualTo("2026-11-02T00:30:00.123Z");
        assertThat(data.path("endAt").asText()).isEqualTo("2026-11-02T01:30:00Z");
        assertThat(data.path("groupIds").size()).isEqualTo(2);
        assertThat(data.path("colorKey").asText()).isEqualTo("team");
        assertThat(data.path("allDay").asBoolean()).isFalse();
    }

    @Test
    void errorWrapperKeepsCodeAndOmitsNullData() throws Exception {
        var response = send("/json-contract/error", null);
        JsonNode root = mapper.readTree(response.body());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("code").asText()).isEqualTo("C001");
        assertThat(root.path("message").asText()).isEqualTo("fixture error");
        assertThat(root.has("data")).isFalse();
    }

    private HttpResponse<String> send(String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (json != null) request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class})
    @Import(ProbeController.class)
    static class TestApplication {
        @Bean
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/json-contract/auth")
        ApiResponse<AuthResponse> auth() {
            return ApiResponse.ok(new AuthResponse("fixture-access", "fixture-refresh", null, false));
        }

        @PostMapping("/json-contract/event")
        ApiResponse<EventCreateRequest> event(@Valid @RequestBody EventCreateRequest request) {
            return ApiResponse.ok(request);
        }

        @GetMapping("/json-contract/error")
        ApiResponse<Void> error() { return ApiResponse.error("C001", "fixture error"); }
    }
}

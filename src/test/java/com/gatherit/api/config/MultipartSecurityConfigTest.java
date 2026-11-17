package com.gatherit.api.config;

import com.gatherit.api.common.notify.SlackNotifier;
import com.gatherit.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real loopback HTTP/Tomcat parsing; no application bootstrap, DB, storage or external API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MultipartSecurityConfigTest.TestApplication.class,
        properties = {"spring.profiles.active=multipart-contract", "server.address=127.0.0.1",
                "spring.jpa.hibernate.ddl-auto=none", "logging.level.org.hibernate.SQL=OFF"})
class MultipartSecurityConfigTest {
    private static final String BOUNDARY = "gatherit-safe-multipart-fixture";
    @LocalServerPort int port;
    @Autowired ProbeController controller;
    @Autowired SlackNotifier slack;

    @BeforeEach
    void clearCalls() {
        controller.calls.set(0);
    }

    @Test
    void thirtyPhotosAndBatchIdPassThroughRealParser() throws Exception {
        var response = postMultipart(30, "photo.jpg");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"files\":30", "\"batchId\":\"retry-fixture\"");
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    void thirtyPhotosWithUtf8FileNamesRemainSupported() throws Exception {
        var response = postMultipart(30, "모임-사진.jpg");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"files\":30");
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    void thirtyPhotosWithLongRfc5987Utf8FileNamesRemainSupported() throws Exception {
        var response = postMultipart(30, "가".repeat(150) + ".jpg", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"files\":30");
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    void excessPartsAreRejectedBeforeControllerWithout500Alert() throws Exception {
        var response = postMultipart(MultipartSecurityConfig.MAX_PART_COUNT, "photo.jpg");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"C001\"");
        assertThat(controller.calls.get()).isZero();
        verifyNoInteractions(slack);
    }

    @Test
    void oversizedPartHeaderIsRejectedBeforeControllerWithout500Alert() throws Exception {
        var response = postMultipart(1, "a".repeat(5000) + ".jpg");
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"PH005\"");
        assertThat(controller.calls.get()).isZero();
        verifyNoInteractions(slack);
    }

    private HttpResponse<String> postMultipart(int files, String filename) throws Exception {
        return postMultipart(files, filename, false);
    }

    private HttpResponse<String> postMultipart(int files, String filename, boolean extendedFilename) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < files; i++) {
            body.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"files\"; ");
            if (extendedFilename) {
                body.append("filename*=UTF-8''")
                        .append(URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
            } else {
                body.append("filename=\"").append(filename).append("\"");
            }
            body.append("\r\nContent-Type: image/jpeg\r\n\r\nx\r\n");
        }
        body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"batchId\"\r\n\r\nretry-fixture\r\n")
                .append("--").append(BOUNDARY).append("--\r\n");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/multipart-contract"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toString().getBytes(StandardCharsets.UTF_8)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class})
    @Import({MultipartSecurityConfig.class, GlobalExceptionHandler.class, ProbeController.class})
    static class TestApplication {
        @Bean SlackNotifier slackNotifier() { return mock(SlackNotifier.class); }
        @Bean SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class ProbeController {
        final AtomicInteger calls = new AtomicInteger();
        @PostMapping(value = "/multipart-contract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        Map<String, Object> upload(@RequestPart("files") List<MultipartFile> files,
                                  @RequestParam("batchId") String batchId) {
            calls.incrementAndGet();
            return Map.of("files", files.size(), "batchId", batchId);
        }
    }
}

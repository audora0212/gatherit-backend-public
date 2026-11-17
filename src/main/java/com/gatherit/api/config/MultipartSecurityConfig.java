package com.gatherit.api.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bound multipart parser memory without breaking the app's 30-photo batch. */
@Configuration(proxyBeanMethods = false)
public class MultipartSecurityConfig {

    // 30 files plus batchId and one spare form field. Application-level file limits remain 30.
    static final int MAX_PART_COUNT = 32;
    // Leave room for UTF-8/RFC 5987 filenames while retaining a finite per-part bound.
    static final int MAX_PART_HEADER_SIZE = 4096;

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> multipartParserLimits() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setMaxPartCount(MAX_PART_COUNT);
            connector.setMaxPartHeaderSize(MAX_PART_HEADER_SIZE);
        });
    }
}

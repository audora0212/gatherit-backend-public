package com.gatherit.api.auth.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatherit.api.auth.dto.RefreshRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshRequestCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); // Spring Boot default

    record LegacyRequest(String refreshToken) {}

    @Test
    void oldAppRequestIsAcceptedWithoutAttemptAndOldServerIgnoresNewOptionalField() throws Exception {
        assertThat(mapper.readValue("{\"refreshToken\":\"old\"}", RefreshRequest.class).refreshAttemptId()).isNull();
        String body = mapper.writeValueAsString(new RefreshRequest("old", "a".repeat(64)));
        assertThat(mapper.readValue(body, LegacyRequest.class).refreshToken()).isEqualTo("old");
    }

    @Test
    void dtoAllowsOnlyNullOrLowercaseSixtyFourHexCharacters() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RefreshRequest("token", null))).isEmpty();
            assertThat(validator.validate(new RefreshRequest("token", "a".repeat(64)))).isEmpty();
            assertThat(validator.validate(new RefreshRequest("token", "A".repeat(64)))).isNotEmpty();
            assertThat(validator.validate(new RefreshRequest("token", "a".repeat(63)))).isNotEmpty();
        }
    }
}

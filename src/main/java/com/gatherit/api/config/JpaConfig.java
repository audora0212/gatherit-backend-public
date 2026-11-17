package com.gatherit.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 — BaseEntity 의 @CreatedDate / @LastModifiedDate 가 동작하려면 필수.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}

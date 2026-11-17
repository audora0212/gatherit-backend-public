package com.gatherit.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads YAML and one synthetic environment key only; no context, datasource or real environment. */
class ProductionDatabasePolicyTest {

    @Test
    void commonLocalConfigurationKeepsItsExistingUpdatePolicy() throws IOException {
        assertThat(bind(sources(false)).getDdlAuto()).isEqualTo("update");
    }

    @Test
    void productionConfigurationDefaultsToValidationWithoutAutomaticSchemaChanges() throws IOException {
        assertThat(bind(sources(true)).getDdlAuto()).isEqualTo("validate");
    }

    @Test
    void neitherYamlFileOverridesTheDdlPolicyThroughRawHibernateOrJpaSettings() throws IOException {
        for (boolean production : new boolean[]{false, true}) {
            var resolver = new PropertySourcesPropertyResolver(sources(production));
            for (String key : new String[]{
                    "spring.jpa.properties.hibernate.hbm2ddl.auto",
                    "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
                    "spring.jpa.properties.javax.persistence.schema-generation.database.action",
                    "hibernate.hbm2ddl.auto"}) {
                assertThat(resolver.containsProperty(key)).as(key).isFalse();
            }
        }
    }

    @Test
    void syntheticServiceEnvironmentEnforcesValidationAboveLegacyCommonConfiguration() throws IOException {
        var sources = sources(false);
        sources.addFirst(new SystemEnvironmentPropertySource("synthetic-service-environment",
                Map.of("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate")));

        assertThat(bind(sources).getDdlAuto()).isEqualTo("validate");
    }

    private static HibernateProperties bind(MutablePropertySources sources) {
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.jpa.hibernate", Bindable.of(HibernateProperties.class)).get();
    }

    private static MutablePropertySources sources(boolean production) throws IOException {
        var loader = new YamlPropertySourceLoader();
        var sources = new MutablePropertySources();
        for (var source : loader.load("common-database-policy", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        if (production) {
            for (var source : loader.load("production-database-policy",
                    new ClassPathResource("application-prod.yml"))) {
                sources.addFirst(source);
            }
        }
        return sources;
    }
}

package com.gatherit.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Binds packaged YAML only; does not start Spring, resolve credentials, or connect to a database. */
class ErrorResponsePropertiesCompatibilityTest {

    @Test
    void commonYamlKeepsTheExistingDevelopmentErrorPolicy() throws IOException {
        ErrorProperties policy = bind(sources(false));

        assertThat(policy.getIncludeMessage()).isEqualTo(ErrorProperties.IncludeAttribute.ALWAYS);
        assertThat(policy.getIncludeBindingErrors()).isEqualTo(ErrorProperties.IncludeAttribute.ALWAYS);
    }

    @Test
    void productionYamlOverridesCommonPolicyAndDoesNotExposeInternalDetails() throws IOException {
        ErrorProperties policy = bind(sources(true));

        assertThat(policy.getIncludeMessage()).isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
        assertThat(policy.getIncludeBindingErrors()).isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
        assertThat(policy.getIncludeStacktrace()).isEqualTo(ErrorProperties.IncludeAttribute.NEVER);
        assertThat(policy.isIncludeException()).isFalse();
    }

    @Test
    void bothYamlFilesNoLongerUseRemovedServerErrorKeys() throws IOException {
        for (boolean production : new boolean[]{false, true}) {
            var resolver = new PropertySourcesPropertyResolver(sources(production));
            for (String key : new String[]{"include-message", "include-binding-errors",
                    "include-stacktrace", "include-exception"}) {
                assertThat(resolver.containsProperty("server.error." + key)).isFalse();
            }
        }
    }

    private static ErrorProperties bind(MutablePropertySources sources) {
        // Restrict binding to the error policy. Datasource, JWT, and external service
        // placeholders in these files are never resolved or instantiated.
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.web.error", Bindable.of(ErrorProperties.class)).get();
    }

    private static MutablePropertySources sources(boolean production) throws IOException {
        var loader = new YamlPropertySourceLoader();
        var sources = new MutablePropertySources();
        for (var source : loader.load("common-error-policy", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        if (production) {
            for (var source : loader.load("production-error-policy",
                    new ClassPathResource("application-prod.yml"))) {
                sources.addFirst(source);
            }
        }
        return sources;
    }
}

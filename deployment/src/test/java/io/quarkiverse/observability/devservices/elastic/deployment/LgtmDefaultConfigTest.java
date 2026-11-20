package io.quarkiverse.observability.devservices.elastic.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * The extension ships {@code META-INF/microprofile-config.properties} in its runtime jar to switch the Grafana LGTM
 * dev service off. These tests load the config the way Quarkus does (classpath sources, ordinal 100).
 */
class LgtmDefaultConfigTest {

    private static final String LGTM_ENABLED = "quarkus.observability.lgtm.enabled";

    private static SmallRyeConfigBuilder builder() {
        return new SmallRyeConfigBuilder().addDefaultSources();
    }

    @Test
    void lgtmIsDisabledByDefault() {
        SmallRyeConfig config = builder().build();

        assertEquals("false", config.getRawValue(LGTM_ENABLED));
    }

    @Test
    void explicitUserSettingOverridesTheDefault() {
        // Same ordinal as application.properties (250), which is what a user would normally use.
        SmallRyeConfig config = builder()
                .withSources(new PropertiesConfigSource(Map.of(LGTM_ENABLED, "true"), "application.properties", 250))
                .build();

        assertEquals("true", config.getRawValue(LGTM_ENABLED));
    }
}

package io.quarkiverse.observability.devservices.elastic.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.observability.devservices.elastic")
public interface ElasticDevServicesBuildTimeConfig {

    /**
     * Enables the Elastic observability dev service stack in dev/test.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Enables container reuse for Testcontainers mode.
     *
     * Disabled by default so containers are stopped when the app/test run ends.
     */
    @WithDefault("false")
    boolean reuse();

    /**
     * Elasticsearch container image.
     */
    @WithDefault("elasticsearch:9.5.5")
    String elasticsearchImage();

    /**
     * Kibana container image.
     */
    @WithDefault("kibana:9.5.5")
    String kibanaImage();

    /**
     * Elastic APM Server container image.
     */
    @WithDefault("elastic/apm-server:9.5.5")
    String apmServerImage();

    /**
     * OpenTelemetry Collector container image.
     */
    @WithDefault("otel/opentelemetry-collector-contrib:0.164.0")
    String otelCollectorImage();

    /**
     * Optional path to a custom OpenTelemetry Collector configuration file.
     *
     * When not set, the extension generates a default collector configuration.
     */
    Optional<String> otelCollectorConfig();

    /**
     * Host port used to expose Elasticsearch.
     *
     * The Elastic stack still communicates internally on the standard Elasticsearch port (9200).
     */
    @WithDefault("9200")
    int elasticsearchPort();

    /**
     * Host port used to expose Kibana.
     *
     * The Elastic stack still communicates internally on the standard Kibana port (5601).
     */
    @WithDefault("5601")
    int kibanaPort();

    /**
     * Host port used to expose the Elastic APM Server.
     *
     * The Elastic stack still communicates internally on the standard APM Server port (8200).
     */
    @WithDefault("8200")
    int apmServerPort();

    /**
     * Host port used to expose the OTLP gRPC receiver.
     *
     * The OpenTelemetry Collector still listens internally on the standard OTLP gRPC port (4317).
     */
    @WithDefault("4317")
    int otlpGrpcPort();

    /**
     * Host port used to expose the OTLP HTTP receiver.
     *
     * The OpenTelemetry Collector still listens internally on the standard OTLP HTTP port (4318).
     */
    @WithDefault("4318")
    int otlpHttpPort();

    /**
     * Compose-mode specific options.
     */
    ComposeConfig compose();

    interface ComposeConfig {

        /**
         * Optional extension compose file path used when global compose files are not configured.
         *
         * Precedence in compose mode:
         * 1. quarkus.compose.devservices.files from the consuming application
         * 2. this extension key
         * 3. generated compose file under target/quarkus-devservices/elastic/
         */
        Optional<String> files();

        /**
         * Default compose project name used when not set globally by the consuming application.
         */
        @WithDefault("observability-elastic")
        String projectName();

    }
}


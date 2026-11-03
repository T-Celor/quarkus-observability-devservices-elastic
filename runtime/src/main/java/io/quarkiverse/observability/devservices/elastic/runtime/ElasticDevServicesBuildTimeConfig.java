package io.quarkiverse.observability.devservices.elastic.runtime;

import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Same shape as the Quarkus Grafana LGTM dev service ({@code quarkus.observability.lgtm.*}): {@code enabled},
 * {@code enabled-in-tests}, {@code shared} with a label and a service name, one optional fixed host port per service.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.observability.elastic")
public interface ElasticDevServicesBuildTimeConfig {

    /**
     * If Dev Services has been explicitly enabled or disabled.
     * <p>
     * When enabled, Quarkus starts the Elastic observability stack in dev mode (and in test mode when
     * {@code enabled-in-tests} is set) while a container runtime is available.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether to start the Elastic observability Dev Services in test mode.
     * <p>
     * Disabled by default, like the LGTM dev service. It has no effect in dev mode.
     */
    @WithDefault("false")
    boolean enabledInTests();

    /**
     * Whether the stack is shared between applications.
     * <p>
     * When shared, Quarkus first looks for running containers carrying the labels below, then for services of a Compose
     * Dev Services project, and only starts new containers when nothing matches. Sharing is only used in dev mode.
     */
    @WithDefault("true")
    boolean shared();

    /**
     * Prefix of the label attached to each started container (suffixed with the service name, for example
     * {@code quarkus-dev-service-elastic-kibana}). Used for discovery when {@code shared} is {@code true}.
     */
    @WithDefault("quarkus-dev-service-elastic")
    String label();

    /**
     * The value of the labels attached to the started containers. Used for discovery when {@code shared} is {@code true}.
     */
    @WithDefault("elastic")
    String serviceName();

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
     * <p>
     * When not set, the extension generates a default collector configuration.
     */
    Optional<String> otelCollectorConfig();

    /**
     * Host port of Elasticsearch. If set it is a fixed value, otherwise a free port is picked.
     */
    OptionalInt elasticsearchPort();

    /**
     * Host port of Kibana. If set it is a fixed value, otherwise a free port is picked.
     */
    OptionalInt kibanaPort();

    /**
     * Host port of the Elastic APM Server. If set it is a fixed value, otherwise a free port is picked.
     */
    OptionalInt apmServerPort();

    /**
     * Host port of the OTLP gRPC receiver. If set it is a fixed value, otherwise a free port is picked.
     */
    OptionalInt otlpGrpcPort();

    /**
     * Host port of the OTLP HTTP receiver. If set it is a fixed value, otherwise a free port is picked.
     */
    OptionalInt otlpHttpPort();
}

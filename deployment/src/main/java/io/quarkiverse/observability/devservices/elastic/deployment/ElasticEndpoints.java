package io.quarkiverse.observability.devservices.elastic.deployment;

import java.util.LinkedHashMap;
import java.util.Map;

/** Where the stack is reachable from the application, whether it was started, discovered by label or found in Compose. */
record ElasticEndpoints(String host, int elasticsearchPort, int kibanaPort, int apmServerPort,
        int otlpGrpcPort, int otlpHttpPort) {

    static final String OTLP_ENDPOINT_KEY = "quarkus.otel.exporter.otlp.endpoint";
    static final String CONFIG_PREFIX = "observability.elastic.";

    /**
     * The config exposed to the application. The OTLP endpoint is only set when OpenTelemetry is in the application,
     * as the LGTM dev service does.
     */
    Map<String, String> toConfig(boolean openTelemetryPresent) {
        Map<String, String> config = new LinkedHashMap<>();
        if (openTelemetryPresent) {
            config.put(OTLP_ENDPOINT_KEY, "http://" + host + ":" + otlpGrpcPort + "/");
        }
        config.put(CONFIG_PREFIX + "host", host);
        config.put(CONFIG_PREFIX + "elasticsearch-port", String.valueOf(elasticsearchPort));
        config.put(CONFIG_PREFIX + "kibana-port", String.valueOf(kibanaPort));
        config.put(CONFIG_PREFIX + "apm-server-port", String.valueOf(apmServerPort));
        config.put(CONFIG_PREFIX + "otlp-grpc-port", String.valueOf(otlpGrpcPort));
        config.put(CONFIG_PREFIX + "otlp-http-port", String.valueOf(otlpHttpPort));
        return config;
    }

    static java.util.Set<String> configKeys(boolean openTelemetryPresent) {
        return new ElasticEndpoints("", 0, 0, 0, 0, 0).toConfig(openTelemetryPresent).keySet();
    }

    String describe() {
        return "elasticsearch=" + elasticsearchPort + ", kibana=" + kibanaPort + ", apm-server=" + apmServerPort
                + ", otlp-grpc=" + otlpGrpcPort + ", otlp-http=" + otlpHttpPort;
    }
}

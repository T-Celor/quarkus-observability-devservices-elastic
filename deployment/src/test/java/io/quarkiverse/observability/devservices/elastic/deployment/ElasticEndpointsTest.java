package io.quarkiverse.observability.devservices.elastic.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ElasticEndpointsTest {

    private final ElasticEndpoints endpoints = new ElasticEndpoints("localhost", 1, 2, 3, 4, 5);

    @Test
    void exposesOtlpEndpointAndPortsWhenOpenTelemetryIsPresent() {
        Map<String, String> config = endpoints.toConfig(true);

        assertEquals("http://localhost:4/", config.get("quarkus.otel.exporter.otlp.endpoint"));
        assertEquals("localhost", config.get("observability.elastic.host"));
        assertEquals("1", config.get("observability.elastic.elasticsearch-port"));
        assertEquals("2", config.get("observability.elastic.kibana-port"));
        assertEquals("3", config.get("observability.elastic.apm-server-port"));
        assertEquals("4", config.get("observability.elastic.otlp-grpc-port"));
        assertEquals("5", config.get("observability.elastic.otlp-http-port"));
    }

    @Test
    void doesNotSetOtlpEndpointWithoutOpenTelemetry() {
        Map<String, String> config = endpoints.toConfig(false);

        assertNull(config.get("quarkus.otel.exporter.otlp.endpoint"));
        assertFalse(config.isEmpty());
    }
}

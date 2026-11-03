package io.quarkiverse.observability.devservices.elastic.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;

public abstract class AbstractElasticObservabilityDevServicesEndpointTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);
    private static final int SOCKET_CONNECT_TIMEOUT_MS = 1500;
    private static final int HTTP_TIMEOUT_MS = 3000;

    @Test
    void shouldStartDevServicesAndExposeReachableEndpoints() {
        if (!verifyDevServicesEndpoints()) {
            assertNotNull(ConfigProvider.getConfig(), "Runtime config should be available");
            return;
        }

        int elasticsearchPort = resolvedPort("elasticsearch-port", 9200);
        int kibanaPort = resolvedPort("kibana-port", 5601);
        int apmServerPort = resolvedPort("apm-server-port", 8200);
        int otlpHttpPort = resolvedPort("otlp-http-port", 4318);
        String otlpExporterEndpoint = getRequiredConfigValue("quarkus.otel.exporter.otlp.endpoint");

        assertNotNull(otlpExporterEndpoint, "OTLP exporter endpoint must be configured");

        URI otlpUri = URI.create(otlpExporterEndpoint);
        assertTrue(otlpUri.getHost() != null && !otlpUri.getHost().isBlank(),
                "OTLP exporter endpoint must include a host");
        assertTrue(otlpUri.getPort() > 0, "OTLP exporter endpoint must include a port");
        assertEquals("http", otlpUri.getScheme(), "OTLP exporter endpoint should use HTTP scheme");
        if (expectManagedOtlpEndpoint()) {
            assertEquals("127.0.0.1", otlpUri.getHost(),
                    "OTLP exporter endpoint should target 127.0.0.1 from the test process");
        }
        assertPortOpen("otlp-exporter", otlpUri.getHost(), otlpUri.getPort());

        assertHttpResponsive("elasticsearch", elasticsearchPort);
        assertHttpResponsive("kibana", kibanaPort);
        assertHttpResponsive("apm-server", apmServerPort);
        assertHttpResponsive("otlp-http", otlpHttpPort);
    }

    private static String getRequiredConfigValue(String name) {
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue(name, String.class)
                .orElseThrow(() -> new AssertionError("Missing required config: " + name));
    }

    // The resolved key is only set when the extension picked the ports itself (Testcontainers mode).
    private static int resolvedPort(String key, int defaultValue) {
        String prefix = "quarkus.observability.devservices.elastic.";
        return getConfigValue("observability.devservices.elastic.resolved." + key, getConfigValue(prefix + key, defaultValue));
    }

    private static int getConfigValue(String name, int defaultValue) {
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue(name, Integer.class).orElse(defaultValue);
    }

    protected boolean expectManagedOtlpEndpoint() {
        return true;
    }

    protected boolean verifyDevServicesEndpoints() {
        return true;
    }

    private static void assertHttpResponsive(String endpointName, int port) {
        eventually(endpointName + " HTTP endpoint", () -> {
            Response response = RestAssured
                    .given()
                    .config(httpTimeoutConfig())
                    .redirects().follow(false)
                    .when()
                    .get("http://127.0.0.1:" + port + "/");
            int status = response.getStatusCode();
            return status > 0 && status < 600;
        });
    }

    private static RestAssuredConfig httpTimeoutConfig() {
        return RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", HTTP_TIMEOUT_MS)
                .setParam("http.socket.timeout", HTTP_TIMEOUT_MS));
    }

    private static void assertPortOpen(String endpointName, String host, int port) {
        eventually(endpointName + " TCP endpoint", () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), SOCKET_CONNECT_TIMEOUT_MS);
                return true;
            }
        });
    }

    private static void eventually(String operationName, CheckedBooleanSupplier assertion) {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        Throwable lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                if (assertion.getAsBoolean()) {
                    return;
                }
            } catch (Throwable t) {
                lastFailure = t;
            }

            try {
                Thread.sleep(RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for " + operationName + " to become ready", e);
            }
        }

        if (lastFailure == null) {
            fail(operationName + " did not become ready within " + STARTUP_TIMEOUT.toSeconds() + " seconds");
        }
        fail(operationName + " did not become ready within " + STARTUP_TIMEOUT.toSeconds() + " seconds", lastFailure);
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws IOException;
    }
}



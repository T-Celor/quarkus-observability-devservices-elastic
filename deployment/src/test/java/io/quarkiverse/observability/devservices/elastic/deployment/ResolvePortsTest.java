package io.quarkiverse.observability.devservices.elastic.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.observability.devservices.elastic.deployment.ElasticObservabilityDevServicesProcessor.PortResolution;
import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;

class ResolvePortsTest {

    private static ElasticDevServicesBuildTimeConfig config(int kibanaPort) {
        return (ElasticDevServicesBuildTimeConfig) Proxy.newProxyInstance(
                ResolvePortsTest.class.getClassLoader(), new Class<?>[] { ElasticDevServicesBuildTimeConfig.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("kibanaPort")) {
                        return kibanaPort;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0; // closed port, never in use
                    }
                    return method.getReturnType() == Optional.class ? Optional.empty() : "image:1";
                });
    }

    @Test
    void busyDefaultPortIsReplaced() throws IOException {
        try (ServerSocket busy = new ServerSocket(0)) {
            PortResolution resolution = ElasticObservabilityDevServicesProcessor
                    .resolvePorts(config(busy.getLocalPort()), ports -> Map.of());
            assertNotEquals(busy.getLocalPort(), resolution.kibanaPort());
        }
    }

    @Test
    void busyExplicitPortFails() throws IOException {
        String key = "quarkus.observability.devservices.elastic.kibana-port";
        try (ServerSocket busy = new ServerSocket(0)) {
            System.setProperty(key, String.valueOf(busy.getLocalPort()));
            assertThrows(IllegalStateException.class, () -> ElasticObservabilityDevServicesProcessor
                    .resolvePorts(config(busy.getLocalPort()), ports -> Map.of()));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void partialStackThatShutsDownIsWaitedOut() throws IOException {
        try (ServerSocket open = new ServerSocket(0)) {
            int[] calls = { 0 };
            // First look: kibana alone is running (a stack mid-shutdown); second look: it is gone.
            PortResolution resolution = ElasticObservabilityDevServicesProcessor.resolvePorts(config(open.getLocalPort()),
                    ports -> calls[0]++ == 0 ? Map.of(open.getLocalPort(), "image:1") : Map.of());
            assertEquals(2, calls[0]);
            assertNotEquals(open.getLocalPort(), resolution.kibanaPort());
        }
    }
}


package io.quarkiverse.observability.devservices.elastic.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;

class ElasticDevServicesConfigTest {

    /** A config whose ports come from the given map (name -> port); every other port is unset (random). */
    private static ElasticDevServicesBuildTimeConfig config(Map<String, Integer> ports) {
        return (ElasticDevServicesBuildTimeConfig) Proxy.newProxyInstance(
                ElasticDevServicesConfigTest.class.getClassLoader(),
                new Class<?>[] { ElasticDevServicesBuildTimeConfig.class },
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == OptionalInt.class) {
                        Integer port = ports.get(method.getName());
                        return port == null ? OptionalInt.empty() : OptionalInt.of(port);
                    }
                    if (type == Optional.class) {
                        return Optional.empty();
                    }
                    return type == boolean.class ? true : "label";
                });
    }

    @Test
    void unsetPortsAreNotChecked() {
        assertDoesNotThrow(() -> ElasticObservabilityDevServicesProcessor.validateUniqueHostPorts(config(Map.of())));
    }

    @Test
    void distinctFixedPortsAreAccepted() {
        assertDoesNotThrow(() -> ElasticObservabilityDevServicesProcessor
                .validateUniqueHostPorts(config(Map.of("elasticsearchPort", 9200, "kibanaPort", 5601))));
    }

    @Test
    void rejectsTheSameFixedPortForTwoServices() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ElasticObservabilityDevServicesProcessor
                        .validateUniqueHostPorts(config(Map.of("elasticsearchPort", 9200, "kibanaPort", 9200))));
        assertTrue(failure.getMessage().contains("9200"));
    }

    @Test
    void labelsAreOnePerService() {
        assertEquals("label-kibana", ElasticDiscovery.label(config(Map.of()), "kibana"));
    }
}

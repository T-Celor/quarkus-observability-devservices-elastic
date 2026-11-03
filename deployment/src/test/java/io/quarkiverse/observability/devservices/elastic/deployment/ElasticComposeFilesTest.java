package io.quarkiverse.observability.devservices.elastic.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;

class ElasticComposeFilesTest {

    @Test
    @SuppressWarnings("unchecked")
    void generatedComposeFileIsValidYamlWithApmCommand() {
        ElasticDevServicesBuildTimeConfig config = (ElasticDevServicesBuildTimeConfig) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { ElasticDevServicesBuildTimeConfig.class },
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == int.class) {
                        return 1000;
                    }
                    return type == Optional.class ? Optional.empty() : "image:1";
                });

        Map<String, Object> compose = new Yaml().load(ElasticComposeFiles.composeFile(config, "./c.yaml"));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> apm = (Map<String, Object>) services.get("apm-server");

        // Docker Compose rejects an empty "environment:" ("must be a mapping"); YAML parses it as null.
        services.forEach((name, service) -> assertFalse(((Map<String, Object>) service).containsKey("environment")
                && ((Map<String, Object>) service).get("environment") == null, name + " has an empty environment"));
        assertEquals(List.of(ElasticStackTopology.apmServerCommand()), apm.get("command"));
        assertEquals(List.of("127.0.0.1:1000:9200"), ((Map<String, Object>) services.get("elasticsearch")).get("ports"));
    }
}


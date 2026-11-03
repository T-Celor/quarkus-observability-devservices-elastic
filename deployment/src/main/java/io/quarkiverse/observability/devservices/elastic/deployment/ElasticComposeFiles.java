package io.quarkiverse.observability.devservices.elastic.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;

final class ElasticComposeFiles {

    private ElasticComposeFiles() {
    }

    static Path ensureComposeFile(ElasticDevServicesBuildTimeConfig config, Path buildDir) {
        String configuredComposeFile = config.compose().files().orElse(null);
        if (configuredComposeFile != null && !configuredComposeFile.isBlank()) {
            return Path.of(configuredComposeFile).toAbsolutePath().normalize();
        }

        try {
            Path outputDir = buildDir.resolve("quarkus-devservices").resolve("elastic").toAbsolutePath().normalize();
            Files.createDirectories(outputDir);

            String collectorConfigSource = prepareCollectorConfig(config, outputDir);

            Path composeFile = outputDir.resolve("compose-devservices.yml");
            Files.writeString(composeFile, composeFile(config, collectorConfigSource), StandardCharsets.UTF_8);
            return composeFile;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot generate compose devservices files", e);
        }
    }

    /** Validated custom collector config path, or empty when the generated one must be used. */
    static Optional<Path> customCollectorConfig(ElasticDevServicesBuildTimeConfig config) {
        return config.otelCollectorConfig().filter(value -> !value.isBlank()).map(value -> {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Custom otel collector config file does not exist: " + path);
            }
            return path;
        });
    }

    private static String prepareCollectorConfig(ElasticDevServicesBuildTimeConfig config, Path outputDir) throws IOException {
        Optional<Path> customConfig = customCollectorConfig(config);
        if (customConfig.isPresent()) {
            return customConfig.get().toString().replace('\\', '/');
        }

        Path generatedCollectorConfig = outputDir.resolve("otel-collector-config.yaml");
        Files.writeString(generatedCollectorConfig, ElasticStackTopology.collectorConfig(), StandardCharsets.UTF_8);
        return "./otel-collector-config.yaml";
    }

    static String composeFile(ElasticDevServicesBuildTimeConfig config, String collectorConfigSource) {
        return String.format("""
                services:
                  elasticsearch:
                    image: %s
                    environment:
                      - discovery.type=single-node
                      - xpack.security.enabled=false
                      - ES_JAVA_OPTS=-Xms512m -Xmx512m
                    networks:
                      - elastic
                    ports:
                      - \"127.0.0.1:%d:%d\"

                  kibana:
                    image: %s
                    environment:
                      - ELASTICSEARCH_HOSTS=%s
                      - SERVER_PORT=%d
                    depends_on:
                      - elasticsearch
                    networks:
                      - elastic
                    ports:
                      - \"127.0.0.1:%d:%d\"

                  apm-server:
                    image: %s
                    cap_add:
                      - CHOWN
                      - DAC_OVERRIDE
                      - SETGID
                      - SETUID
                    environment:
                    command:
                      %s
                    depends_on:
                      - elasticsearch
                      - kibana
                    networks:
                      - elastic
                    ports:
                      - \"127.0.0.1:%d:%d\"

                  otel-collector:
                    image: %s
                    command:
                      - --config
                      - /etc/otel-collector-config.yaml
                    volumes:
                      - "%s:/etc/otel-collector-config.yaml:ro"
                    depends_on:
                      - apm-server
                    networks:
                      - elastic
                    ports:
                      - \"127.0.0.1:%d:%d\"
                      - \"127.0.0.1:%d:%d\"

                networks:
                  elastic:
                    driver: bridge
                """,
                config.elasticsearchImage(),
                config.elasticsearchPort(), ElasticStackPorts.ELASTICSEARCH,
                config.kibanaImage(),
                ElasticStackTopology.elasticsearchUrl(), ElasticStackPorts.KIBANA,
                config.kibanaPort(), ElasticStackPorts.KIBANA,
                config.apmServerImage(),
                Arrays.stream(ElasticStackTopology.apmServerCommand())
                        .map(arg -> "- '" + arg + "'")
                        .collect(Collectors.joining("\n      ")),
                config.apmServerPort(), ElasticStackPorts.APM_SERVER,
                config.otelCollectorImage(),
                collectorConfigSource,
                config.otlpGrpcPort(), ElasticStackPorts.OTLP_GRPC,
                config.otlpHttpPort(), ElasticStackPorts.OTLP_HTTP);
    }
}


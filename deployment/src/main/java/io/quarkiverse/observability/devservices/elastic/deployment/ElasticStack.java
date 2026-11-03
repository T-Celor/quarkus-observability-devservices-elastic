package io.quarkiverse.observability.devservices.elastic.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;
import io.quarkus.deployment.builditem.Startable;

/** The four containers of the stack, owned by Quarkus: it starts them, restarts them when the config changes and closes them. */
final class ElasticStack implements Startable {

    static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private final ElasticDevServicesBuildTimeConfig config;
    private final Duration startupTimeout;
    private Network network;
    private GenericContainer<?> elasticsearch;
    private GenericContainer<?> kibana;
    private GenericContainer<?> apmServer;
    private GenericContainer<?> otelCollector;
    private Path collectorConfig;
    private boolean collectorConfigGenerated;

    ElasticStack(ElasticDevServicesBuildTimeConfig config, Optional<Duration> startupTimeout) {
        this.config = config;
        this.startupTimeout = startupTimeout.orElse(DEFAULT_STARTUP_TIMEOUT);
    }

    @Override
    public void start() {
        try {
            prepareCollectorConfig();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create generated collector config", e);
        }

        List<GenericContainer<?>> containers = new ArrayList<>();
        try {
            network = Network.newNetwork();
            elasticsearch = container(config.elasticsearchImage(), ElasticStackTopology.ELASTICSEARCH_SERVICE)
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("http.port", String.valueOf(ElasticStackPorts.ELASTICSEARCH))
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(startupTimeout));
            expose(elasticsearch, config.elasticsearchPort(), ElasticStackPorts.ELASTICSEARCH);
            containers.add(elasticsearch);
            elasticsearch.start();

            kibana = container(config.kibanaImage(), ElasticStackTopology.KIBANA_SERVICE)
                    .withEnv("ELASTICSEARCH_HOSTS", ElasticStackTopology.elasticsearchUrl())
                    .withEnv("SERVER_PORT", String.valueOf(ElasticStackPorts.KIBANA))
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(startupTimeout));
            expose(kibana, config.kibanaPort(), ElasticStackPorts.KIBANA);
            containers.add(kibana);
            kibana.start();

            apmServer = container(config.apmServerImage(), ElasticStackTopology.APM_SERVER_SERVICE)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(startupTimeout))
                    .withCommand(ElasticStackTopology.apmServerCommand())
                    .withCreateContainerCmdModifier(cmd -> {
                        HostConfig hostConfig = cmd.getHostConfig();
                        if (hostConfig == null) {
                            hostConfig = HostConfig.newHostConfig();
                            cmd.withHostConfig(hostConfig);
                        }
                        hostConfig.withCapAdd(Capability.CHOWN, Capability.DAC_OVERRIDE, Capability.SETGID,
                                Capability.SETUID);
                    });
            expose(apmServer, config.apmServerPort(), ElasticStackPorts.APM_SERVER);
            containers.add(apmServer);
            apmServer.start();

            otelCollector = container(config.otelCollectorImage(), ElasticStackTopology.OTEL_COLLECTOR_SERVICE)
                    .withCopyFileToContainer(MountableFile.forHostPath(collectorConfig, 0644),
                            "/etc/otel-collector-config.yaml")
                    // The collector image has no /bin/sh, so the default port check cannot run.
                    .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1).withStartupTimeout(startupTimeout))
                    .withCommand("--config", "/etc/otel-collector-config.yaml");
            expose(otelCollector, config.otlpGrpcPort(), ElasticStackPorts.OTLP_GRPC);
            expose(otelCollector, config.otlpHttpPort(), ElasticStackPorts.OTLP_HTTP);
            containers.add(otelCollector);
            otelCollector.start();
        } catch (RuntimeException | Error failure) {
            cleanupFailedStart(containers, failure);
            throw failure;
        }
    }

    private GenericContainer<?> container(String image, String service) {
        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(service)
                // The label is what a later dev session looks for when the stack is shared.
                .withLabel(ElasticDiscovery.label(config, service), config.serviceName());
    }

    /**
     * A fixed host port when configured, otherwise a free one picked by Docker. Always bound to 127.0.0.1 only:
     * Elasticsearch runs without security, so it must not be reachable from the network.
     */
    private static void expose(GenericContainer<?> container, OptionalInt hostPort, int containerPort) {
        container.addExposedPort(containerPort);
        container.withCreateContainerCmdModifier(cmd -> {
            HostConfig hostConfig = cmd.getHostConfig();
            if (hostConfig == null) {
                hostConfig = HostConfig.newHostConfig();
                cmd.withHostConfig(hostConfig);
            }
            if (hostConfig.getPortBindings() == null) {
                hostConfig.withPortBindings(new Ports());
            }
            Ports.Binding binding = hostPort.isPresent()
                    ? Ports.Binding.bindIpAndPort("127.0.0.1", hostPort.getAsInt())
                    : Ports.Binding.bindIp("127.0.0.1");
            hostConfig.getPortBindings().getBindings().put(ExposedPort.tcp(containerPort),
                    new Ports.Binding[] { binding });
        });
    }

    ElasticEndpoints endpoints() {
        return new ElasticEndpoints(otelCollector.getHost(),
                elasticsearch.getMappedPort(ElasticStackPorts.ELASTICSEARCH),
                kibana.getMappedPort(ElasticStackPorts.KIBANA),
                apmServer.getMappedPort(ElasticStackPorts.APM_SERVER),
                otelCollector.getMappedPort(ElasticStackPorts.OTLP_GRPC),
                otelCollector.getMappedPort(ElasticStackPorts.OTLP_HTTP));
    }

    private void prepareCollectorConfig() throws IOException {
        Optional<Path> custom = config.otelCollectorConfig().filter(value -> !value.isBlank()).map(value -> {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Custom otel collector config file does not exist: " + path);
            }
            return path;
        });
        if (custom.isPresent()) {
            collectorConfig = custom.get();
            collectorConfigGenerated = false;
            return;
        }
        collectorConfig = Files.createTempFile("quarkus-otel-collector-", ".yaml");
        Files.writeString(collectorConfig, ElasticStackTopology.collectorConfig(), StandardCharsets.UTF_8);
        collectorConfigGenerated = true;
    }

    private void cleanupFailedStart(List<GenericContainer<?>> containers, Throwable failure) {
        for (int i = containers.size() - 1; i >= 0; i--) {
            try {
                containers.get(i).stop();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (network != null) {
            try {
                network.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        deleteGeneratedCollectorConfig(failure);
    }

    private void deleteGeneratedCollectorConfig(Throwable failure) {
        if (collectorConfigGenerated) {
            try {
                Files.deleteIfExists(collectorConfig);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public String getConnectionInfo() {
        return otelCollector == null ? "not started" : endpoints().describe();
    }

    @Override
    public String getContainerId() {
        return otelCollector == null ? null : otelCollector.getContainerId();
    }

    @Override
    public void close() throws IOException {
        try {
            stopQuietly(otelCollector);
            stopQuietly(apmServer);
            stopQuietly(kibana);
            stopQuietly(elasticsearch);
            if (network != null) {
                network.close();
            }
        } finally {
            if (collectorConfigGenerated) {
                Files.deleteIfExists(collectorConfig);
            }
        }
    }

    private static void stopQuietly(GenericContainer<?> container) {
        if (container == null) {
            return;
        }
        try {
            container.stop();
        } catch (RuntimeException ignored) {
            // Intentionally ignored on shutdown path.
        }
    }
}

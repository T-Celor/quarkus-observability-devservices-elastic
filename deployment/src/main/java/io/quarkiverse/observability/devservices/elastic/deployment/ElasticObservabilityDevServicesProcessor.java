package io.quarkiverse.observability.devservices.elastic.deployment;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.Startable;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;

public class ElasticObservabilityDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(ElasticObservabilityDevServicesProcessor.class);
    private static final String FEATURE = "observability-devservices-elastic";
    private static final int PORT_PROBE_TIMEOUT_MS = 300;
    private static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final String OTLP_ENDPOINT_KEY = "quarkus.otel.exporter.otlp.endpoint";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void start(ElasticDevServicesBuildTimeConfig config,
               LaunchModeBuildItem launchMode,
               OutputTargetBuildItem outputTarget,
               BuildProducer<RunTimeConfigurationDefaultBuildItem> defaults,
               BuildProducer<DevServicesResultBuildItem> devServices) {
        if (!launchMode.getLaunchMode().isDevServicesSupported()) {
            LOG.debugf("Skipping Elastic observability Dev Services because launch mode %s does not support Dev Services",
                    launchMode.getLaunchMode());
            return;
        }

        if (!config.enabled()) {
            LOG.debug("Skipping Elastic observability Dev Services because it is disabled by configuration");
            return;
        }

        validateUniqueHostPorts(config);

        if (isComposeDevServicesEnabled()) {
            Optional<String> applicationComposeFiles = configuredComposeFiles();
            if (applicationComposeFiles.isPresent()) {
                LOG.infof("Compose Dev Services is enabled and consuming application configured quarkus.compose.devservices.files=%s",
                        applicationComposeFiles.get());
            } else {
                Path composeFile = ElasticComposeFiles.ensureComposeFile(config, outputTarget.getOutputDirectory());
                defaults.produce(new RunTimeConfigurationDefaultBuildItem(
                        "quarkus.compose.devservices.files",
                        composeFile.toString().replace('\\', '/')));
                LOG.infof("Compose Dev Services is enabled; using extension compose file %s", composeFile);
            }

            defaults.produce(new RunTimeConfigurationDefaultBuildItem(
                    "quarkus.compose.devservices.project-name",
                    config.compose().projectName()));
            warnAboutBusyPorts(config);
            devServices.produce(DevServicesResultBuildItem.discovered()
                    .feature(FEATURE)
                    .config(Map.of(OTLP_ENDPOINT_KEY, ElasticStackTopology.otlpGrpcEndpoint(config.otlpGrpcPort())))
                    .build());
            return;
        }

        PortResolution resolution = resolvePorts(config);
        Map<String, String> applicationConfig = applicationConfig(resolution);
        if (resolution.fullExistingStack()) {
            LOG.infof("Reusing existing Elastic observability stack on localhost ports %s", resolution.describe());
            devServices.produce(DevServicesResultBuildItem.discovered()
                    .feature(FEATURE)
                    .config(applicationConfig)
                    .build());
            return;
        }

        LOG.infof("Starting Elastic observability Dev Services stack with Testcontainers on host ports %s",
                resolution.describe());
        // Quarkus owns the lifecycle: it starts, restarts on live reload when the config changes, and closes the stack.
        devServices.produce(DevServicesResultBuildItem.<ElasticStack> owned()
                .feature(FEATURE)
                .serviceConfig(config)
                .startable(() -> new ElasticStack(config, resolution))
                .config(applicationConfig)
                .build());
    }

    // Warn only: a stack from an earlier Compose run may legitimately still hold these ports.
    private static void warnAboutBusyPorts(ElasticDevServicesBuildTimeConfig config) {
        Map<String, Integer> ports = Map.of("elasticsearch", config.elasticsearchPort(), "kibana", config.kibanaPort(),
                "apm-server", config.apmServerPort(), "otlp-grpc", config.otlpGrpcPort(),
                "otlp-http", config.otlpHttpPort());
        ports.forEach((name, port) -> {
            if (isLocalPortOpen(port)) {
                LOG.warnf("Host port %d for %s is already in use; Compose may fail to start it unless that is the existing stack",
                        port, name);
            }
        });
    }

    /** The OTLP endpoint plus the host ports actually used, which differ from the configured ones after an auto-pick. */
    private static Map<String, String> applicationConfig(PortResolution resolution) {
        String prefix = "observability.devservices.elastic.resolved.";
        return Map.of(
                OTLP_ENDPOINT_KEY, ElasticStackTopology.otlpGrpcEndpoint(resolution.otlpGrpcPort()),
                prefix + "elasticsearch-port", String.valueOf(resolution.elasticsearchPort()),
                prefix + "kibana-port", String.valueOf(resolution.kibanaPort()),
                prefix + "apm-server-port", String.valueOf(resolution.apmServerPort()),
                prefix + "otlp-http-port", String.valueOf(resolution.otlpHttpPort()));
    }

    private static boolean isComposeDevServicesEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.compose.devservices.enabled", Boolean.class)
                .orElse(false);
    }

    private static Optional<String> configuredComposeFiles() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.compose.devservices.files", String.class)
                .filter(value -> !value.isBlank());
    }

    private static void validateUniqueHostPorts(ElasticDevServicesBuildTimeConfig config) {
        Map<Integer, String> servicesByPort = new HashMap<>();
        registerHostPort(servicesByPort, config.elasticsearchPort(), "elasticsearch");
        registerHostPort(servicesByPort, config.kibanaPort(), "kibana");
        registerHostPort(servicesByPort, config.apmServerPort(), "apm-server");
        registerHostPort(servicesByPort, config.otlpGrpcPort(), "otlp-grpc");
        registerHostPort(servicesByPort, config.otlpHttpPort(), "otlp-http");
    }

    private static void registerHostPort(Map<Integer, String> servicesByPort, int port, String service) {
        String existingService = servicesByPort.putIfAbsent(port, service);
        if (existingService != null) {
            throw new IllegalStateException("Host port " + port + " is configured for both " + existingService
                    + " and " + service + ". Each Elastic observability service must use a unique host port.");
        }
    }

    private static PortResolution resolvePorts(ElasticDevServicesBuildTimeConfig config) {
        return resolvePorts(config, ElasticObservabilityDevServicesProcessor::runningImagesByHostPorts);
    }

    private static boolean isPartial(List<ServicePortSpec> specs, Map<Integer, String> runningImagesByPort) {
        long reusable = specs.stream().filter(spec -> spec.isReusableExistingPort(runningImagesByPort)).count();
        return reusable > 0 && reusable < specs.size();
    }

    static PortResolution resolvePorts(ElasticDevServicesBuildTimeConfig config,
            Function<Set<Integer>, Map<Integer, String>> runningImages) {
        ServicePortSpec elasticsearch = ServicePortSpec.of("elasticsearch",
                "quarkus.observability.devservices.elastic.elasticsearch-port",
                config.elasticsearchPort(), config.elasticsearchImage());
        ServicePortSpec kibana = ServicePortSpec.of("kibana",
                "quarkus.observability.devservices.elastic.kibana-port",
                config.kibanaPort(), config.kibanaImage());
        ServicePortSpec apmServer = ServicePortSpec.of("apm-server",
                "quarkus.observability.devservices.elastic.apm-server-port",
                config.apmServerPort(), config.apmServerImage());
        ServicePortSpec otlpGrpc = ServicePortSpec.of("otlp-grpc",
                "quarkus.observability.devservices.elastic.otlp-grpc-port",
                config.otlpGrpcPort(), config.otelCollectorImage());
        ServicePortSpec otlpHttp = ServicePortSpec.of("otlp-http",
                "quarkus.observability.devservices.elastic.otlp-http-port",
                config.otlpHttpPort(), config.otelCollectorImage());

        List<ServicePortSpec> specs = List.of(elasticsearch, kibana, apmServer, otlpGrpc, otlpHttp);
        Set<Integer> configuredPorts = specs.stream().map(spec -> spec.configuredPort).collect(Collectors.toSet());
        Map<Integer, String> latest = runningImages.apply(configuredPorts);
        // shortcut: a stack that is still shutting down looks partial; wait up to ~30s for it to go, then fail.
        for (int attempt = 0; attempt < 15 && isPartial(specs, latest); attempt++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            latest = runningImages.apply(configuredPorts);
        }
        Map<Integer, String> runningImagesByPort = latest;
        boolean fullExistingStack = specs.stream().allMatch(spec -> spec.isReusableExistingPort(runningImagesByPort));
        if (fullExistingStack) {
            return new PortResolution(
                    elasticsearch.configuredPort,
                    kibana.configuredPort,
                    apmServer.configuredPort,
                    otlpGrpc.configuredPort,
                    otlpHttp.configuredPort,
                    true);
        }

        List<ServicePortSpec> partiallyReusable = specs.stream()
                .filter(spec -> spec.isReusableExistingPort(runningImagesByPort))
                .toList();
        if (!partiallyReusable.isEmpty()) {
            String details = partiallyReusable.stream()
                    .map(spec -> spec.name + "=" + spec.configuredPort)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Detected a partial existing Elastic observability stack on localhost ports ["
                    + details
                    + "]. For predictable Dev Services behavior, reuse is only supported when the full stack is present. "
                    + "Stop the existing containers or align all configured ports/images.");
        }

        // Seed with every configured port so an auto-picked port cannot collide with a later, still-free service.
        Set<Integer> reservedPorts = specs.stream().map(spec -> spec.configuredPort).collect(Collectors.toCollection(HashSet::new));
        for (ServicePortSpec spec : specs) {
            if (isLocalPortOpen(spec.configuredPort)) {
                Optional<String> occupiedImage = Optional.ofNullable(runningImagesByPort.get(spec.configuredPort));
                if (spec.explicitlyConfigured) {
                    if (occupiedImage.isPresent() && !imagesMatch(spec.expectedImage, occupiedImage.get())) {
                        throw new IllegalStateException("Configured port conflict for " + spec.name + " on localhost:"
                                + spec.configuredPort + ". Expected image " + spec.expectedImage
                                + " but found running image " + occupiedImage.get() + ".");
                    }
                    if (occupiedImage.isPresent()) {
                        throw new IllegalStateException("Configured port for " + spec.name + " is already occupied by matching image "
                                + occupiedImage.get() + " on localhost:" + spec.configuredPort
                                + ". Reuse requires the full Elastic observability stack to be present.");
                    }
                    throw new IllegalStateException("Configured port for " + spec.name + " is already in use: localhost:"
                            + spec.configuredPort + ". Stop the existing process or change the configured port.");
                }
                spec.effectivePort = nextFreePort(reservedPorts);
                LOG.infof("Port %d for %s is already in use%s; using %d instead",
                        spec.configuredPort,
                        spec.name,
                        occupiedImage.map(image -> " by image " + image).orElse(""),
                        spec.effectivePort);
            } else {
                spec.effectivePort = spec.configuredPort;
            }
            reservedPorts.add(spec.effectivePort);
        }

        return new PortResolution(elasticsearch.effectivePort, kibana.effectivePort, apmServer.effectivePort,
                otlpGrpc.effectivePort, otlpHttp.effectivePort, false);
    }

    private static boolean isLocalPortOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Map<Integer, String> runningImagesByHostPorts(Set<Integer> ports) {
        if (ports.isEmpty()) {
            return Map.of();
        }

        try {
            Map<Integer, String> imagesByPort = new HashMap<>();
            List<Container> running = DockerClientFactory.instance().client().listContainersCmd().withShowAll(false).exec();
            for (Container container : running) {
                ContainerPort[] containerPorts = container.getPorts();
                if (containerPorts == null) {
                    continue;
                }
                for (ContainerPort containerPort : containerPorts) {
                    Integer publicPort = containerPort.getPublicPort();
                    if (publicPort != null && ports.contains(publicPort)) {
                        imagesByPort.put(publicPort, container.getImage());
                    }
                }
            }
            return imagesByPort;
        } catch (RuntimeException e) {
            LOG.debug("Cannot inspect running Docker containers for host ports", e);
            return Map.of();
        }
    }

    private static boolean imagesMatch(String expected, String actual) {
        String expectedNormalized = normalizeImageName(expected);
        String actualNormalized = normalizeImageName(actual);
        return expectedNormalized.equalsIgnoreCase(actualNormalized)
                || actualNormalized.endsWith("/" + expectedNormalized)
                || expectedNormalized.endsWith("/" + actualNormalized);
    }

    private static String normalizeImageName(String image) {
        if (image == null) {
            return "";
        }
        String normalized = image.trim();
        int digest = normalized.indexOf('@');
        if (digest > 0) {
            normalized = normalized.substring(0, digest);
        }
        return normalized;
    }

    // shortcut: port is released before Docker binds it, so parallel builds can race; retry the start if that bites.
    private static int nextFreePort(Set<Integer> reservedPorts) {
        while (true) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int candidate = socket.getLocalPort();
                if (!reservedPorts.contains(candidate)) {
                    return candidate;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot allocate a free host port for Elastic observability Dev Services", e);
            }
        }
    }

    private static final class ServicePortSpec {
        private final String name;
        private final int configuredPort;
        private final String expectedImage;
        private final boolean explicitlyConfigured;
        private int effectivePort;

        private ServicePortSpec(String name, int configuredPort, String expectedImage, boolean explicitlyConfigured) {
            this.name = name;
            this.configuredPort = configuredPort;
            this.expectedImage = expectedImage;
            this.explicitlyConfigured = explicitlyConfigured;
            this.effectivePort = configuredPort;
        }

        private static ServicePortSpec of(String name, String key, int configuredPort, String expectedImage) {
            return new ServicePortSpec(name, configuredPort, expectedImage, isExplicitlyConfigured(key));
        }

        private boolean isReusableExistingPort(Map<Integer, String> runningImagesByPort) {
            return isLocalPortOpen(configuredPort)
                    && Optional.ofNullable(runningImagesByPort.get(configuredPort))
                            .map(image -> imagesMatch(expectedImage, image)).orElse(false);
        }
    }

    private static boolean isExplicitlyConfigured(String key) {
        // getConfigValue resolves env-var names and profiles; @WithDefault values live in DefaultValuesConfigSource.
        ConfigValue value = ConfigProvider.getConfig().getConfigValue(key);
        return value.getValue() != null && !"DefaultValuesConfigSource".equals(value.getSourceName());
    }

    record PortResolution(int elasticsearchPort, int kibanaPort, int apmServerPort,
                                  int otlpGrpcPort, int otlpHttpPort, boolean fullExistingStack) {

        private String describe() {
            return "elasticsearch=" + elasticsearchPort
                    + ", kibana=" + kibanaPort
                    + ", apm-server=" + apmServerPort
                    + ", otlp-grpc=" + otlpGrpcPort
                    + ", otlp-http=" + otlpHttpPort;
        }
    }

    private static final class ElasticStack implements Startable {
        private final ElasticDevServicesBuildTimeConfig config;
        private final PortResolution ports;
        private Network network;
        private GenericContainer<?> elasticsearch;
        private GenericContainer<?> kibana;
        private GenericContainer<?> apmServer;
        private GenericContainer<?> otelCollector;
        private CollectorConfig collectorConfig;

        private ElasticStack(ElasticDevServicesBuildTimeConfig config, PortResolution ports) {
            this.config = config;
            this.ports = ports;
        }

        @Override
        public void start() {
            try {
                collectorConfig = collectorConfig(config);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create generated collector config", e);
            }

            List<GenericContainer<?>> containers = new ArrayList<>();
            try {
                network = Network.newNetwork();
                elasticsearch = new GenericContainer<>(config.elasticsearchImage())
                        .withNetwork(network)
                        .withNetworkAliases(ElasticStackTopology.ELASTICSEARCH_SERVICE)
                        .withEnv("discovery.type", "single-node")
                        .withEnv("xpack.security.enabled", "false")
                        .withEnv("http.port", String.valueOf(ElasticStackPorts.ELASTICSEARCH))
                        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                        .withExposedPorts(ElasticStackPorts.ELASTICSEARCH)
                        .waitingFor(Wait.forListeningPort().withStartupTimeout(CONTAINER_STARTUP_TIMEOUT))
                        .withReuse(config.reuse());
                bindFixedPort(elasticsearch, ports.elasticsearchPort(), ElasticStackPorts.ELASTICSEARCH);
                containers.add(elasticsearch);
                elasticsearch.start();

                kibana = new GenericContainer<>(config.kibanaImage())
                        .withNetwork(network)
                        .withNetworkAliases(ElasticStackTopology.KIBANA_SERVICE)
                        .withEnv("ELASTICSEARCH_HOSTS", ElasticStackTopology.elasticsearchUrl())
                        .withEnv("SERVER_PORT", String.valueOf(ElasticStackPorts.KIBANA))
                        .withExposedPorts(ElasticStackPorts.KIBANA)
                        .waitingFor(Wait.forListeningPort().withStartupTimeout(CONTAINER_STARTUP_TIMEOUT))
                        .withReuse(config.reuse());
                bindFixedPort(kibana, ports.kibanaPort(), ElasticStackPorts.KIBANA);
                containers.add(kibana);
                kibana.start();

                apmServer = new GenericContainer<>(config.apmServerImage())
                        .withNetwork(network)
                        .withNetworkAliases(ElasticStackTopology.APM_SERVER_SERVICE)
                        .withExposedPorts(ElasticStackPorts.APM_SERVER)
                        .waitingFor(Wait.forListeningPort().withStartupTimeout(CONTAINER_STARTUP_TIMEOUT))
                        .withCommand(ElasticStackTopology.apmServerCommand())
                        .withCreateContainerCmdModifier(cmd -> {
                            HostConfig hostConfig = cmd.getHostConfig();
                            if (hostConfig == null) {
                                hostConfig = HostConfig.newHostConfig();
                                cmd.withHostConfig(hostConfig);
                            }
                            hostConfig.withCapAdd(
                                    Capability.CHOWN,
                                    Capability.DAC_OVERRIDE,
                                    Capability.SETGID,
                                    Capability.SETUID);
                        })
                        .withReuse(config.reuse());
                bindFixedPort(apmServer, ports.apmServerPort(), ElasticStackPorts.APM_SERVER);
                containers.add(apmServer);
                apmServer.start();

                otelCollector = new GenericContainer<>(config.otelCollectorImage())
                        .withNetwork(network)
                        .withNetworkAliases(ElasticStackTopology.OTEL_COLLECTOR_SERVICE)
                        .withCopyFileToContainer(MountableFile.forHostPath(collectorConfig.path, 0644),
                                "/etc/otel-collector-config.yaml")
                        .withExposedPorts(ElasticStackPorts.OTLP_GRPC, ElasticStackPorts.OTLP_HTTP)
                        // The collector image has no /bin/sh, so the default port check cannot run.
                        .waitingFor(Wait.forLogMessage(".*Everything is ready.*", 1)
                                .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT))
                        .withCommand("--config", "/etc/otel-collector-config.yaml")
                        .withReuse(config.reuse());
                bindFixedPort(otelCollector, ports.otlpGrpcPort(), ElasticStackPorts.OTLP_GRPC);
                bindFixedPort(otelCollector, ports.otlpHttpPort(), ElasticStackPorts.OTLP_HTTP);
                containers.add(otelCollector);
                otelCollector.start();

            } catch (RuntimeException | Error failure) {
                cleanupFailedStart(containers, network, collectorConfig, failure);
                throw failure;
            }
        }

        private static void cleanupFailedStart(List<GenericContainer<?>> containers, Network network,
                                               CollectorConfig collectorConfig, Throwable failure) {
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
            if (collectorConfig.generated) {
                try {
                    Files.deleteIfExists(collectorConfig.path);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }

        private static CollectorConfig collectorConfig(ElasticDevServicesBuildTimeConfig config) throws IOException {
            Optional<Path> customConfig = ElasticComposeFiles.customCollectorConfig(config);
            if (customConfig.isPresent()) {
                return new CollectorConfig(customConfig.get(), false);
            }

            Path generatedConfig = Files.createTempFile("quarkus-otel-collector-", ".yaml");
            Files.writeString(generatedConfig, ElasticStackTopology.collectorConfig(), StandardCharsets.UTF_8);
            return new CollectorConfig(generatedConfig, true);
        }

        @Override
        public String getConnectionInfo() {
            return ElasticStackTopology.otlpGrpcEndpoint(ports.otlpGrpcPort());
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
                if (collectorConfig != null && collectorConfig.generated) {
                    Files.deleteIfExists(collectorConfig.path);
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

        private static void bindFixedPort(GenericContainer<?> container, int hostPort, int containerPort) {
            List<String> bindings = new ArrayList<>(container.getPortBindings());
            bindings.add("127.0.0.1:" + hostPort + ":" + containerPort);
            container.setPortBindings(bindings);
        }

        private static final class CollectorConfig {
            private final Path path;
            private final boolean generated;

            private CollectorConfig(Path path, boolean generated) {
                this.path = path;
                this.generated = generated;
            }
        }

    }
}


package io.quarkiverse.observability.devservices.elastic.deployment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jboss.logging.Logger;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.IsDevServicesSupportedByLaunchMode;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildSteps;
import io.quarkus.deployment.builditem.DevServicesComposeProjectBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.DockerStatusBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.runtime.LaunchMode;

/**
 * Same flow as the Quarkus LGTM dev service: gate on launch mode, config and Docker, then reuse what is already
 * running (shared containers, then Compose Dev Services), and only then start a stack owned by Quarkus.
 */
@BuildSteps(onlyIf = { IsDevServicesSupportedByLaunchMode.class, DevServicesConfig.Enabled.class })
public class ElasticObservabilityDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(ElasticObservabilityDevServicesProcessor.class);
    private static final String FEATURE = "observability-devservices-elastic";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void start(ElasticDevServicesBuildTimeConfig config,
            LaunchModeBuildItem launchMode,
            DockerStatusBuildItem dockerStatus,
            DevServicesComposeProjectBuildItem composeProject,
            DevServicesConfig devServicesConfig,
            Capabilities capabilities,
            BuildProducer<DevServicesResultBuildItem> devServices) {
        if (!config.enabled()) {
            LOG.debug("Elastic observability Dev Services are disabled in config");
            return;
        }

        LaunchMode mode = launchMode.getLaunchMode();
        if (mode == LaunchMode.TEST && !config.enabledInTests()) {
            LOG.debug("Elastic observability Dev Services are disabled in test mode by default. "
                    + "Set quarkus.observability.elastic.enabled-in-tests=true to enable.");
            return;
        }

        if (!dockerStatus.isContainerRuntimeAvailable()) {
            LOG.warn("Please get a working Docker instance to start the Elastic observability Dev Services");
            return;
        }

        validateUniqueHostPorts(config);

        boolean openTelemetry = capabilities.isPresent(Capability.OPENTELEMETRY_TRACER)
                || capabilities.isPresent(Capability.OPENTELEMETRY_METRICS)
                || capabilities.isPresent(Capability.OPENTELEMETRY_LOGS);

        Optional<ElasticEndpoints> shared = ElasticDiscovery.locateShared(config, mode);
        if (shared.isPresent()) {
            LOG.infof("Elastic observability Dev Services re-used, %s", shared.get().describe());
            devServices.produce(discovered(shared.get(), openTelemetry));
            return;
        }

        Optional<ElasticEndpoints> compose = ElasticDiscovery.locateInCompose(config, composeProject, mode);
        if (compose.isPresent()) {
            LOG.infof("Elastic observability stack found in Compose Dev Services, %s", compose.get().describe());
            devServices.produce(discovered(compose.get(), openTelemetry));
            return;
        }

        LOG.info("Starting Elastic observability Dev Services stack with Testcontainers");
        // Quarkus owns the lifecycle: it starts, restarts on live reload when the config changes, and closes the stack.
        devServices.produce(DevServicesResultBuildItem.<ElasticStack> owned()
                .feature(FEATURE)
                .serviceName(config.serviceName())
                .serviceConfig(config)
                .startable(() -> new ElasticStack(config, devServicesConfig.timeout()))
                .configProvider(configProvider(openTelemetry))
                .postStartHook(stack -> LOG.infof("Elastic observability Dev Services started, %s",
                        stack.endpoints().describe()))
                .build());
    }

    // The keys do not depend on the ports, only the values do: they are read once the containers are up.
    private static Map<String, Function<ElasticStack, String>> configProvider(boolean openTelemetry) {
        Map<String, Function<ElasticStack, String>> provider = new LinkedHashMap<>();
        for (String key : ElasticEndpoints.configKeys(openTelemetry)) {
            provider.put(key, stack -> stack.endpoints().toConfig(openTelemetry).get(key));
        }
        return provider;
    }

    private static DevServicesResultBuildItem discovered(ElasticEndpoints endpoints, boolean openTelemetry) {
        return DevServicesResultBuildItem.discovered()
                .feature(FEATURE)
                .config(endpoints.toConfig(openTelemetry))
                .build();
    }

    static void validateUniqueHostPorts(ElasticDevServicesBuildTimeConfig config) {
        Map<Integer, String> servicesByPort = new HashMap<>();
        registerHostPort(servicesByPort, config.elasticsearchPort(), "elasticsearch");
        registerHostPort(servicesByPort, config.kibanaPort(), "kibana");
        registerHostPort(servicesByPort, config.apmServerPort(), "apm-server");
        registerHostPort(servicesByPort, config.otlpGrpcPort(), "otlp-grpc");
        registerHostPort(servicesByPort, config.otlpHttpPort(), "otlp-http");
    }

    private static void registerHostPort(Map<Integer, String> servicesByPort, java.util.OptionalInt port,
            String service) {
        if (port.isEmpty()) {
            return;
        }
        String existingService = servicesByPort.putIfAbsent(port.getAsInt(), service);
        if (existingService != null) {
            throw new IllegalStateException("Host port " + port.getAsInt() + " is configured for both "
                    + existingService + " and " + service
                    + ". Each Elastic observability service must use a unique host port.");
        }
    }
}

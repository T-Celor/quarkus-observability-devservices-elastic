package io.quarkiverse.observability.devservices.elastic.deployment;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.testcontainers.DockerClientFactory;

import io.quarkiverse.observability.devservices.elastic.runtime.ElasticDevServicesBuildTimeConfig;
import io.quarkus.deployment.builditem.DevServicesComposeProjectBuildItem;
import io.quarkus.deployment.dev.devservices.ContainerInfo;
import io.quarkus.deployment.dev.devservices.RunningContainer;
import io.quarkus.devservices.common.ComposeLocator;
import io.quarkus.devservices.common.ContainerAddress;
import io.quarkus.devservices.common.ContainerLocator;
import io.quarkus.runtime.LaunchMode;

/**
 * Finds a stack that is already running instead of starting a new one, the way the LGTM dev service does:
 * first by label (shared containers), then among the services of a Compose Dev Services project.
 */
final class ElasticDiscovery {

    private ElasticDiscovery() {
    }

    /** Label key of one service's container, e.g. {@code quarkus-dev-service-elastic-kibana}. */
    static String label(ElasticDevServicesBuildTimeConfig config, String service) {
        return config.label() + "-" + service;
    }

    static Optional<ElasticEndpoints> locateShared(ElasticDevServicesBuildTimeConfig config, LaunchMode launchMode) {
        if (!config.shared()) {
            return Optional.empty();
        }
        Optional<ContainerAddress> elasticsearch = locate(config, launchMode, ElasticStackTopology.ELASTICSEARCH_SERVICE,
                ElasticStackPorts.ELASTICSEARCH);
        Optional<ContainerAddress> kibana = locate(config, launchMode, ElasticStackTopology.KIBANA_SERVICE,
                ElasticStackPorts.KIBANA);
        Optional<ContainerAddress> apm = locate(config, launchMode, ElasticStackTopology.APM_SERVER_SERVICE,
                ElasticStackPorts.APM_SERVER);
        Optional<ContainerAddress> otlpGrpc = locate(config, launchMode, ElasticStackTopology.OTEL_COLLECTOR_SERVICE,
                ElasticStackPorts.OTLP_GRPC);
        Optional<ContainerAddress> otlpHttp = locate(config, launchMode, ElasticStackTopology.OTEL_COLLECTOR_SERVICE,
                ElasticStackPorts.OTLP_HTTP);
        // Atomic like the rest of the stack: a partial match is not reused.
        if (elasticsearch.isEmpty() || kibana.isEmpty() || apm.isEmpty() || otlpGrpc.isEmpty() || otlpHttp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ElasticEndpoints(otlpGrpc.get().getHost(), elasticsearch.get().getPort(),
                kibana.get().getPort(), apm.get().getPort(), otlpGrpc.get().getPort(), otlpHttp.get().getPort()));
    }

    private static Optional<ContainerAddress> locate(ElasticDevServicesBuildTimeConfig config, LaunchMode launchMode,
            String service, int privatePort) {
        return new ContainerLocator(label(config, service), privatePort)
                .locateContainer(config.serviceName(), config.shared(), launchMode);
    }

    static Optional<ElasticEndpoints> locateInCompose(ElasticDevServicesBuildTimeConfig config,
            DevServicesComposeProjectBuildItem composeProject, LaunchMode launchMode) {
        OptionalInt elasticsearch = composePort(config.elasticsearchImage(), ElasticStackTopology.ELASTICSEARCH_SERVICE,
                ElasticStackPorts.ELASTICSEARCH, composeProject, launchMode);
        OptionalInt kibana = composePort(config.kibanaImage(), ElasticStackTopology.KIBANA_SERVICE,
                ElasticStackPorts.KIBANA, composeProject, launchMode);
        OptionalInt apm = composePort(config.apmServerImage(), ElasticStackTopology.APM_SERVER_SERVICE,
                ElasticStackPorts.APM_SERVER, composeProject, launchMode);
        OptionalInt otlpGrpc = composePort(config.otelCollectorImage(), ElasticStackTopology.OTEL_COLLECTOR_SERVICE,
                ElasticStackPorts.OTLP_GRPC, composeProject, launchMode);
        OptionalInt otlpHttp = composePort(config.otelCollectorImage(), ElasticStackTopology.OTEL_COLLECTOR_SERVICE,
                ElasticStackPorts.OTLP_HTTP, composeProject, launchMode);
        if (elasticsearch.isEmpty() || kibana.isEmpty() || apm.isEmpty() || otlpGrpc.isEmpty() || otlpHttp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ElasticEndpoints(DockerClientFactory.instance().dockerHostIpAddress(),
                elasticsearch.getAsInt(), kibana.getAsInt(), apm.getAsInt(), otlpGrpc.getAsInt(), otlpHttp.getAsInt()));
    }

    // A Compose service is recognised by its image or by its service name, as the LGTM dev service does.
    private static OptionalInt composePort(String image, String service, int privatePort,
            DevServicesComposeProjectBuildItem composeProject, LaunchMode launchMode) {
        List<RunningContainer> containers = ComposeLocator.locateContainer(composeProject, List.of(image, service),
                launchMode);
        for (RunningContainer container : containers) {
            for (ContainerInfo.ContainerPort port : container.containerInfo().exposedPorts()) {
                if (port.privatePort() != null && port.privatePort() == privatePort && port.publicPort() != null) {
                    return OptionalInt.of(port.publicPort());
                }
            }
        }
        return OptionalInt.empty();
    }
}

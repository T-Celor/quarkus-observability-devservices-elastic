# Quarkus Observability Dev Services Elastic

Modelled on the Quarkus Grafana LGTM dev service (`quarkus-observability-devservices-lgtm`): same configuration shape,
same discovery order, same launch-mode and Docker gates.

### Purpose

- Start a local Elastic observability stack (Elasticsearch, Kibana, APM Server, OpenTelemetry Collector) for Quarkus dev and test workflows.
- When OpenTelemetry is in the application, set `quarkus.otel.exporter.otlp.endpoint` to the OTLP gRPC receiver of the collector.

### Stack Topology

- Internal container-to-container communication always uses stable service aliases and standard container ports:
  - Kibana -> `http://elasticsearch:9200`
  - APM Server -> `http://elasticsearch:9200` and `http://kibana:5601`
  - OpenTelemetry Collector -> `http://apm-server:8200`
- Host ports are random unless fixed in the configuration.

### Configuration

- Prefix: `quarkus.observability.elastic.*`
- Keys:
  - `enabled` (default `true`)
  - `enabled-in-tests` (default `false`; no effect in dev mode)
  - `shared` (default `true`; dev mode only)
  - `label` (default `quarkus-dev-service-elastic`; suffixed with the service name on each container)
  - `service-name` (default `elastic`; value of those labels)
  - `elasticsearch-image`, `kibana-image`, `apm-server-image`, `otel-collector-image`
  - `otel-collector-config` (optional custom collector YAML file)
  - `elasticsearch-port`, `kibana-port`, `apm-server-port`, `otlp-grpc-port`, `otlp-http-port` (optional; fixed host port when set, random otherwise)

### Start-up Flow

1. Skip when the launch mode does not support Dev Services, Dev Services are disabled, `enabled=false`, test mode without `enabled-in-tests`, or no container runtime is available.
2. `shared=true`: reuse containers carrying the `label` / `service-name` labels. All five endpoints must be found; a partial stack is not reused.
3. Look for the stack in a Compose Dev Services project: a service is recognised by its image or by its service name
   (`elasticsearch`, `kibana`, `apm-server`, `otel-collector`). Compose files are never generated or parsed by the extension.
4. Otherwise start the four containers with Testcontainers; Quarkus owns their lifecycle.

### Published Config

- `quarkus.otel.exporter.otlp.endpoint` (only when OpenTelemetry is present): `http://<host>:<otlp-grpc-port>/`
- `observability.elastic.host` and `observability.elastic.{elasticsearch,kibana,apm-server,otlp-grpc,otlp-http}-port`

### Generated Collector Configuration

- Receivers: OTLP gRPC on `0.0.0.0:4317`, OTLP HTTP on `0.0.0.0:4318`
- Processor: `batch`
- Exporter: OTLP HTTP to `http://apm-server:8200`
- Pipelines: `metrics`, `traces`

### Testing

- Keep one JVM test (`@QuarkusTest`) and one packaged test (`@QuarkusIntegrationTest`).
- The integration tests need `quarkus.observability.elastic.enabled-in-tests=true` and `quarkus-opentelemetry`.
- Validate that the OTLP endpoint points to the published gRPC port and that every endpoint is reachable.
- Use bounded retries because container startup ordering is asynchronous.

### Common Pitfalls

- Do not inject `@ConfigProperty` fields in shared `@QuarkusIntegrationTest` bases; use runtime config lookup instead.
- Without OpenTelemetry in the application the stack still starts but no OTLP endpoint is published.
- `shared` only applies in dev mode.

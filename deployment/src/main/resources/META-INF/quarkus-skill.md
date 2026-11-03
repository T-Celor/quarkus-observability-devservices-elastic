# Quarkus Observability Dev Services Elastic - Skill Guide

### Purpose

- Start a local Elastic observability stack for Quarkus dev/test workflows and, when OpenTelemetry is present, set `quarkus.otel.exporter.otlp.endpoint`.
- Follows the Quarkus Grafana LGTM dev service: same config shape, discovery order and gates.

### Configuration Model

- Prefix: `quarkus.observability.elastic.*`
- Toggles:
  - `enabled` (default `true`)
  - `enabled-in-tests` (default `false`; test mode only)
  - `shared` (default `true`; dev mode only)
- Discovery labels (used when `shared=true`):
  - `label` (default `quarkus-dev-service-elastic`, suffixed with the service name)
  - `service-name` (default `elastic`)
- Images:
  - `elasticsearch-image` (default `elasticsearch:9.5.5`)
  - `kibana-image` (default `kibana:9.5.5`)
  - `apm-server-image` (default `elastic/apm-server:9.5.5`)
  - `otel-collector-image` (default `otel/opentelemetry-collector-contrib:0.164.0`)
  - `otel-collector-config` (optional custom collector YAML file)
- Ports (optional; fixed host port when set, a free port otherwise):
  - `elasticsearch-port`, `kibana-port`, `apm-server-port`, `otlp-grpc-port`, `otlp-http-port`

### Start-up Flow

1. Gates: launch mode supports Dev Services, `enabled`, test mode needs `enabled-in-tests`, a container runtime is available.
2. Shared containers found by label (dev mode, `shared=true`): reuse them. A partial stack is not reused.
3. Services of a Compose Dev Services project recognised by image or service name (`elasticsearch`, `kibana`, `apm-server`, `otel-collector`): use them.
   The extension never generates or parses compose files; use `quarkus.compose.devservices.files` in the application for that.
4. Otherwise start the four containers with Testcontainers.

### Runtime Config Produced

- `quarkus.otel.exporter.otlp.endpoint`: `http://<host>:<otlp-grpc-port>/`, only when OpenTelemetry is in the application.
- `observability.elastic.host` and `observability.elastic.<service>-port` for each service.
- The OTLP endpoint is a default, so applications can still set their own.

### Networking Model

- Internal stack communication always uses standard service aliases and container ports:
  - Kibana -> `http://elasticsearch:9200`
  - APM Server -> `http://elasticsearch:9200` and `http://kibana:5601`
  - OpenTelemetry Collector -> `http://apm-server:8200`

### Testing Practices

- Keep one JVM test (`@QuarkusTest`) and one packaged integration test (`@QuarkusIntegrationTest`).
- Set `quarkus.observability.elastic.enabled-in-tests=true` and add `quarkus-opentelemetry` in the test application.
- Use RestAssured for HTTP readiness checks and socket checks for TCP endpoints, with bounded retries.

### Module Organization

- `runtime`: config interface and extension metadata only.
- `deployment`: build steps (`ElasticObservabilityDevServicesProcessor`), discovery (`ElasticDiscovery`), containers (`ElasticStack`), published config (`ElasticEndpoints`).
- `integration-tests`: black-box validation of the published config and endpoint reachability.

### Common Pitfalls

- Do not inject `@ConfigProperty` fields in `@QuarkusIntegrationTest` classes; prefer runtime config lookup (`ConfigProvider`).
- Ensure `integration-tests` runs Quarkus packaging before Failsafe (`quarkus-maven-plugin` with `build`).
- `quarkus-opentelemetry` also starts the Grafana LGTM dev service by default; set `quarkus.observability.lgtm.enabled=false` to keep only Elastic.

# Quarkus Observability Dev Services Elastic - Skill Guide

### Purpose

- Start a local Elastic observability stack for Quarkus dev/test workflows and auto-configure `quarkus.otel.exporter.otlp.endpoint`.
- Support two orchestration paths:
  - Compose Dev Services when `quarkus.compose.devservices.enabled=true`.
  - Testcontainers for in-process lifecycle management otherwise.

### Configuration Model

- Main prefix: `quarkus.observability.devservices.elastic.*`
- Core toggles:
  - `enabled` (default `true`)
  - `reuse` (default `false`, Testcontainers mode)
- Images:
  - `elasticsearch-image` (default `elasticsearch:9.5.5`)
  - `kibana-image` (default `kibana:9.5.5`)
  - `apm-server-image` (default `elastic/apm-server:9.5.5`)
  - `otel-collector-image` (default `otel/opentelemetry-collector-contrib:0.164.0`)
  - `otel-collector-config` (optional custom collector YAML file)
- Ports:
  - `elasticsearch-port` (default `9200`)
  - `kibana-port` (default `5601`)
  - `apm-server-port` (default `8200`)
  - `otlp-grpc-port` (default `4317`)
  - `otlp-http-port` (default `4318`)

### Compose Dev Services Integration

- Compose sub-config prefix: `quarkus.observability.devservices.elastic.compose.*`
- Supported keys:
  - `files` (optional extension-level compose file path)
  - `project-name` (default `observability-elastic`)
- Compose file precedence in compose mode:
  1. `quarkus.compose.devservices.files` from the consuming app
  2. `quarkus.observability.devservices.elastic.compose.files`
  3. generated extension compose file under `target/quarkus-devservices/elastic/`
- In compose mode, orchestration remains on Compose Dev Services (no Testcontainers fallback).
- Configure compose project-level options directly in the consuming app under `quarkus.compose.devservices.*`.

### Reuse Strategy

- For `testcontainers` mode, reuse is controlled by `quarkus.observability.devservices.elastic.reuse`.
- Reuse is disabled by default so containers stop when the app/test run ends.
- Use a stable compose `project-name` to preserve containers/networks across runs in local dev.

### Runtime Defaults Produced

- The extension publishes:
  - `quarkus.otel.exporter.otlp.endpoint` to the actual host OTLP gRPC endpoint exposed by the extension
    - Compose mode: `http://127.0.0.1:<configured-otlp-grpc-port>/`
    - Testcontainers mode: resolved mapped host endpoint after collector startup (normally the configured port due to fixed mapping)
- Keep this as a default (not forced override) so applications can still set explicit OTLP endpoints.

### Networking Model

- Only the host-published ports are configurable through `*-port` settings.
- Port conflict policy in Testcontainers mode:
  - Reuse existing stack only when all expected ports are occupied by matching images.
  - Fail fast when a partial matching stack is detected.
  - Fail fast if an explicitly configured port is occupied by a different image.
  - Pick another free host port when a non-explicit port is already occupied.
- Internal stack communication always uses standard service aliases and container ports:
  - Kibana -> `http://elasticsearch:9200`
  - APM Server -> `http://elasticsearch:9200` and `http://kibana:5601`
  - OpenTelemetry Collector -> `http://apm-server:8200`
- Keep custom compose files aligned with those published ports if you want the generated OTLP endpoint default to remain accurate.

### Testing Practices

- Keep one JVM test (`@QuarkusTest`) and one packaged integration test (`@QuarkusIntegrationTest`).
- Use RestAssured for HTTP endpoint readiness checks and socket checks for pure TCP endpoints.
- Verify all expected endpoints are reachable:
  - Elasticsearch HTTP
  - Kibana HTTP
  - APM Server HTTP
  - OTLP gRPC TCP
  - OTLP HTTP
- Use bounded retries/timeouts to avoid flaky startup assertions.

### Module Organization

- `runtime`: config interfaces and extension metadata only.
- `deployment`: Dev Services startup logic, orchestration decisions, generated compose assets, default config publication.
- `integration-tests`: black-box validation of produced configuration and endpoint reachability.
- Keep Testcontainers in `deployment` unless reusable orchestration is shared across multiple extensions.

### Common Pitfalls

- Do not inject `@ConfigProperty` fields in `@QuarkusIntegrationTest` classes; prefer runtime config lookup (`ConfigProvider`) in shared test bases.
- Ensure `integration-tests` runs Quarkus packaging before Failsafe (`quarkus-maven-plugin` with `build`) so IT metadata exists.
- Avoid hard-coding environment-specific compose file paths in defaults; use relative paths from app config when needed.
- Do not rely on container reuse in CI unless explicitly required and stable in that environment.


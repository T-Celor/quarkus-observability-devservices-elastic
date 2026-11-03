# Quarkus Observability Dev Services Elastic

### Purpose

- Start a local Elastic observability stack for Quarkus dev and test workflows.
- Auto-configure `quarkus.otel.exporter.otlp.endpoint` to the host-facing OTLP gRPC endpoint exposed by the generated OpenTelemetry Collector.
- Support Compose Dev Services when `quarkus.compose.devservices.enabled=true` and direct Testcontainers orchestration otherwise.

### Stack Topology

- Services started by this extension:
  - Elasticsearch
  - Kibana
  - Elastic APM Server
  - OpenTelemetry Collector
- Internal container-to-container communication always uses stable service aliases and standard container ports:
  - Kibana -> `http://elasticsearch:9200`
  - APM Server -> `http://elasticsearch:9200`
  - APM Server -> `http://kibana:5601`
  - OpenTelemetry Collector -> `http://apm-server:8200`
- Only the host-published ports are configurable.

### Configuration

- Main prefix: `quarkus.observability.devservices.elastic.*`
- Core keys:
  - `enabled` (default `true`)
  - `reuse` (default `false`, Testcontainers mode)
  - `elasticsearch-image` (default `elasticsearch:9.5.5`)
  - `kibana-image` (default `kibana:9.5.5`)
  - `apm-server-image` (default `elastic/apm-server:9.5.5`)
  - `otel-collector-image` (default `otel/opentelemetry-collector-contrib:0.164.0`)
  - `otel-collector-config` (optional custom collector YAML file)
  - `elasticsearch-port` (default `9200`, host-published port)
  - `kibana-port` (default `5601`, host-published port)
  - `apm-server-port` (default `8200`, host-published port)
  - `otlp-grpc-port` (default `4317`, host-published port)
  - `otlp-http-port` (default `4318`, host-published port)
- Compose-specific keys:
  - `compose.files` (optional; used when global compose files are not set)

### Compose Dev Services Integration

- Use global Quarkus Compose activation:
  - `quarkus.compose.devservices.enabled=true`
- Compose file precedence in compose mode:
  1. `quarkus.compose.devservices.files` from the consuming app
  2. `quarkus.observability.devservices.elastic.compose.files`
  3. generated compose assets under `target/quarkus-devservices/elastic/`
- In compose mode, this extension stays on Compose Dev Services (no Testcontainers fallback).
- In Compose mode with app-level compose files, this extension provides a default for:
  - `quarkus.compose.devservices.project-name` from `quarkus.observability.devservices.elastic.compose.project-name`
- Generated assets are written under `target/quarkus-devservices/elastic/`.
- Configure compose project-level options directly in the consuming app under `quarkus.compose.devservices.*`.
- If a custom compose file is provided, keep its published ports aligned with the extension `*-port` settings so `quarkus.otel.exporter.otlp.endpoint` still matches the real host endpoint.

### OTLP Endpoint Publication

- The extension owns the default value of `quarkus.otel.exporter.otlp.endpoint`.
- Compose mode publishes `http://127.0.0.1:<configured-otlp-grpc-port>/`.
- Testcontainers mode publishes the resolved mapped host endpoint after the collector starts.
- With the fixed-port mapping used by this extension, that normally resolves to `http://127.0.0.1:<configured-otlp-grpc-port>/`.

### Port Conflict Policy

- If all expected ports are already occupied by matching stack images, reuse the running stack.
- If only part of the stack is already running on matching-image ports, fail fast (atomic reuse).
- If an explicitly configured port is occupied by a different image, fail fast.
- If a non-explicit port is occupied, choose another free host port for the new stack.

### Generated Collector Configuration

- Receivers:
  - OTLP gRPC on `0.0.0.0:4317`
  - OTLP HTTP on `0.0.0.0:4318`
- Processor:
  - `batch`
- Exporter:
  - OTLP to `http://apm-server:8200`
  - `tls.insecure=true`
- Pipelines:
  - `metrics`
  - `traces`

### Testing

- Keep one JVM test (`@QuarkusTest`) and one packaged test (`@QuarkusIntegrationTest`).
- Validate:
  - `quarkus.otel.exporter.otlp.endpoint` points to the configured host OTLP gRPC port
  - Elasticsearch, Kibana, APM Server, OTLP gRPC, and OTLP HTTP are reachable
- Use RestAssured for HTTP reachability and socket checks for pure TCP validation.
- Use bounded retries because container startup ordering is asynchronous.

### Common Pitfalls

- Do not confuse configurable host ports with fixed internal container ports.
- Do not inject `@ConfigProperty` fields in shared `@QuarkusIntegrationTest` bases; use runtime config lookup instead.
- Avoid `container_name` in generated compose defaults when project isolation or reuse matters.
- Do not assume custom compose files are parsed by the extension; the extension only knows the configured ports it publishes as defaults.


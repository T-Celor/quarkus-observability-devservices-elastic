# Quarkus Observability Dev Services Elastic

This extension provides an Elastic observability stack for Quarkus dev/test and publishes a default OTLP endpoint:

- Elasticsearch
- Kibana
- Elastic APM Server
- OpenTelemetry Collector
- `quarkus.otel.exporter.otlp.endpoint` set to the stack OTLP gRPC host endpoint (when OpenTelemetry is in the application)

It follows the same principles as the Quarkus Grafana LGTM dev service (`quarkus-observability-devservices-lgtm`): the same
configuration shape, the same discovery order and the same launch-mode and Docker checks.

## How it starts

1. Nothing starts when Dev Services are disabled, when `quarkus.observability.elastic.enabled=false`, in test mode unless
   `quarkus.observability.elastic.enabled-in-tests=true`, or when no container runtime is available.
2. `shared=true` (default, dev mode only): containers started earlier and carrying the extension labels are reused.
   The whole stack must be found; a partial stack is not reused.
3. Services of a Compose Dev Services project are reused when they match the stack, by image or by service name
   (`elasticsearch`, `kibana`, `apm-server`, `otel-collector`). The extension does not generate or parse compose files:
   provide your own with `quarkus.compose.devservices.files` if you want Compose.
4. Otherwise the four containers are started with Testcontainers and owned by Quarkus (started, restarted on live reload when the config changes, closed).

Host ports are free random ports unless you fix them with the `*-port` keys.

## Modules

- `runtime`: extension config model and metadata
- `deployment`: Dev Services build steps, discovery and container startup
- `integration-tests`: endpoint reachability checks for JVM and packaged tests
- `docs`: Antora documentation sources

## Build and verification

```bash
cd quarkus-observability-devservices-elastic
mvn clean install
```

Run container-backed extension tests:

```bash
cd quarkus-observability-devservices-elastic
mvn clean install -DskipExtensionTests=false
```

## Use in an app

```xml
<dependency>
  <groupId>io.quarkiverse.observability</groupId>
  <artifactId>quarkus-observability-devservices-elastic</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Prefix: `quarkus.observability.elastic`.

- `enabled=true`
- `enabled-in-tests=false` (no effect in dev mode)
- `shared=true` (dev mode only)
- `label=quarkus-dev-service-elastic` (suffixed with the service name on each container, for example `quarkus-dev-service-elastic-kibana`)
- `service-name=elastic` (value of those labels)
- `elasticsearch-image=elasticsearch:9.5.5`
- `kibana-image=kibana:9.5.5`
- `apm-server-image=elastic/apm-server:9.5.5`
- `otel-collector-image=otel/opentelemetry-collector-contrib:0.164.0`
- `otel-collector-config=` (optional custom collector YAML)
- `elasticsearch-port`, `kibana-port`, `apm-server-port`, `otlp-grpc-port`, `otlp-http-port` (optional: fixed host port when set, random otherwise)

The ports actually used are published to the application as
`observability.elastic.{elasticsearch,kibana,apm-server,otlp-grpc,otlp-http}-port`, with `observability.elastic.host`.

Internal service communication always stays on the standard ports:

- Kibana -> `http://elasticsearch:9200`
- APM Server -> `http://elasticsearch:9200` and `http://kibana:5601`
- Collector -> `http://apm-server:8200`

## Compose example

Reuse a stack you run yourself with Compose Dev Services. Name the services `elasticsearch`, `kibana`, `apm-server` and
`otel-collector` (or use the default images) and publish their standard ports:

```yaml
quarkus:
  compose:
    devservices:
      enabled: true
      files: ./src/main/compose/compose-devservices.yml
```

## Try it

Minimal app: add `quarkus-rest`, `quarkus-opentelemetry` and this extension:

```properties
quarkus.application.name=demo-app
# quarkus-opentelemetry also starts the Grafana LGTM dev service by default; turn it off to keep only the Elastic stack
quarkus.observability.lgtm.enabled=false
```

Run `mvn quarkus:dev`, call an endpoint a few times (`curl localhost:8080/hello`), look for the Kibana port in the log
(`Elastic observability Dev Services started, ... kibana=<port>`) and open `http://localhost:<port>`
(Observability > Applications). The first start pulls the Elastic images, which can take several minutes.

![Kibana APM service inventory showing demo-app](docs/modules/ROOT/images/kibana-apm-services.jpg)

![Kibana APM traces showing GET /hello](docs/modules/ROOT/images/kibana-apm-traces.jpg)

(Screenshots taken with a French-language browser, hence the French Kibana UI.)

## Documentation

- Docs source: `docs/`
- Published guide: `https://docs.quarkiverse.io/quarkus-observability-devservices-elastic/dev/`

## Release readiness

- Track repository and process readiness in `RELEASE_READINESS.md`.

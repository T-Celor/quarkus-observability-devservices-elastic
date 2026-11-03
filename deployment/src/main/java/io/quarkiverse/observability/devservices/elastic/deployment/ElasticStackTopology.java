package io.quarkiverse.observability.devservices.elastic.deployment;

final class ElasticStackTopology {

    static final String ELASTICSEARCH_SERVICE = "elasticsearch";
    static final String KIBANA_SERVICE = "kibana";
    static final String APM_SERVER_SERVICE = "apm-server";
    static final String OTEL_COLLECTOR_SERVICE = "otel-collector";

    private ElasticStackTopology() {
    }

    static String elasticsearchUrl() {
        return httpUrl(ELASTICSEARCH_SERVICE, ElasticStackPorts.ELASTICSEARCH);
    }

    static String kibanaUrl() {
        return httpUrl(KIBANA_SERVICE, ElasticStackPorts.KIBANA);
    }

    static String apmServerUrl() {
        return httpUrl(APM_SERVER_SERVICE, ElasticStackPorts.APM_SERVER);
    }

    static String otlpGrpcEndpoint(int hostPort) {
        return httpUrl("127.0.0.1", hostPort) + "/";
    }

    static String[] apmServerCommand() {
        return new String[] {
                "-e",
                "-E", "apm-server.host=0.0.0.0:" + ElasticStackPorts.APM_SERVER,
                "-E", "apm-server.rum.enabled=true",
                "-E", "setup.kibana.host=" + kibanaUrl(),
                "-E", "output.elasticsearch.hosts=[\"" + elasticsearchUrl() + "\"]",
                "-E", "apm-server.kibana.enabled=true",
                "-E", "apm-server.kibana.host=" + kibanaUrl()
        };
    }

    static String collectorConfig() {
        return String.format("""
                receivers:
                  otlp:
                    protocols:
                      grpc:
                        endpoint: \"0.0.0.0:%d\"
                      http:
                        endpoint: \"0.0.0.0:%d\"

                processors:
                  batch:
                    timeout: 10s
                    send_batch_size: 200

                exporters:
                  otlp_http:
                    endpoint: \"%s\"

                service:
                  pipelines:
                    metrics:
                      receivers: [otlp]
                      processors: [batch]
                      exporters: [otlp_http]
                    traces:
                      receivers: [otlp]
                      processors: [batch]
                      exporters: [otlp_http]
                """, ElasticStackPorts.OTLP_GRPC, ElasticStackPorts.OTLP_HTTP, apmServerUrl());
    }

    private static String httpUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }
}


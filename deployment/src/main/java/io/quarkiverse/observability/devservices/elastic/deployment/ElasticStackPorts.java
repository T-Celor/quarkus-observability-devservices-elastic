package io.quarkiverse.observability.devservices.elastic.deployment;

final class ElasticStackPorts {

    static final int ELASTICSEARCH = 9200;
    static final int KIBANA = 5601;
    static final int APM_SERVER = 8200;
    static final int OTLP_GRPC = 4317;
    static final int OTLP_HTTP = 4318;

    private ElasticStackPorts() {
    }
}



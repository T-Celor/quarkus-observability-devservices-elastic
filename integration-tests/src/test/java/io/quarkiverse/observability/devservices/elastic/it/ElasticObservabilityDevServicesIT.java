package io.quarkiverse.observability.devservices.elastic.it;

import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
class ElasticObservabilityDevServicesIT extends AbstractElasticObservabilityDevServicesEndpointTest {

    @Override
    protected boolean expectManagedOtlpEndpoint() {
        return false;
    }

    @Override
    protected boolean verifyDevServicesEndpoints() {
        return false;
    }
}

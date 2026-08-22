package com.product.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Task 1.3: Actuator's {@code metrics}/{@code info} endpoints are exposed for on-demand incident inspection
 * (design D13, PR3 spec requirement) but must stay behind the same authentication as every other endpoint;
 * only {@code /actuator/health} is explicitly permitted by {@link SecurityConfiguration}. Plain JDK
 * {@link HttpClient} — no new test dependency — against a real random port confirms the unauthenticated
 * status codes end to end, unlike {@code @WebMvcTest} which doesn't load Actuator's endpoints.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ActuatorSecurityIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void metricsIsUnreachableWithoutAuthenticationWhileHealthStaysPublic() throws Exception {
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

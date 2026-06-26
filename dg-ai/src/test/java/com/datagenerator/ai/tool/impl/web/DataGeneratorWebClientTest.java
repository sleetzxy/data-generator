package com.datagenerator.ai.tool.impl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class DataGeneratorWebClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private DataGeneratorWebClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new DataGeneratorWebClient(restTemplate, "http://dg-web.test", "test-token");
    }

    @AfterEach
    void verify() {
        server.verify();
    }

    @Test
    void listConnections_sendsServiceAuthHeader() {
        server.expect(request -> {
                    assertThat(request.getHeaders().getFirst(DataGeneratorWebClient.SERVICE_AUTH_HEADER))
                            .isEqualTo("test-token");
                    assertThat(request.getURI().getPath()).isEqualTo("/api/v1/config/connections");
                })
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "[]", MediaType.APPLICATION_JSON));

        assertThat(client.listConnections()).isEmpty();
    }

    @Test
    void listConnections_http401_includesAuthHint() {
        server.expect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest());

        assertThatThrownBy(() -> client.listConnections())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void listConnections_retriesOnConnectionReset() {
        server.expect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.GET))
                .andRespond(request -> {
                    throw new java.net.SocketException("Connection reset");
                });
        server.expect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "[{\"name\":\"pg-local\",\"type\":\"postgresql\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.listConnections()).hasSize(1);
    }
}

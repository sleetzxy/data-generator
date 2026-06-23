package com.datagenerator.ai.port.http;

import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;

public class HttpConnectionCatalogPort implements ConnectionCatalogPort {

    private static final String CONNECTIONS_PATH = "/api/v1/config/connections";

    private final HttpServiceClient webClient;

    public HttpConnectionCatalogPort(HttpServiceClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<ConnectionInfo> listConnections() {
        return webClient.get(CONNECTIONS_PATH, new TypeReference<>() {});
    }
}

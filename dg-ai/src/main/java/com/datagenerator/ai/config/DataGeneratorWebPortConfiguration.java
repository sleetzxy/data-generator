package com.datagenerator.ai.config;

import com.datagenerator.ai.port.ConnectionCatalogPort;
import com.datagenerator.ai.port.JobDefinitionPort;
import com.datagenerator.ai.port.JobExecutionPort;
import com.datagenerator.ai.port.JobPreviewPort;
import com.datagenerator.ai.port.SchemaCatalogPort;
import com.datagenerator.ai.port.http.HttpConnectionCatalogPort;
import com.datagenerator.ai.port.http.HttpJobDefinitionPort;
import com.datagenerator.ai.port.http.HttpJobExecutionPort;
import com.datagenerator.ai.port.http.HttpJobPreviewPort;
import com.datagenerator.ai.port.http.HttpSchemaCatalogPort;
import com.datagenerator.ai.port.http.HttpServiceClient;
import com.datagenerator.ai.util.TextUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** dg-web 现有 REST API 的 Port HTTP 适配。 */
@Configuration
@ConditionalOnProperty(prefix = "ai", name = "server", havingValue = "true")
public class DataGeneratorWebPortConfiguration {

    public static final String CLIENT_BEAN = "dataGeneratorWebClient";
    private static final String BASE_URL_CONFIG = "ai.remote-services.data-generator-web.base-url";

    @Bean(name = CLIENT_BEAN)
    HttpServiceClient dataGeneratorWebClient(AiProperties properties, ObjectMapper objectMapper) {
        AiProperties.ServiceEndpoint endpoint = properties.getRemoteServices().getDataGeneratorWeb();
        String baseUrl = endpoint.getBaseUrl();
        if (!TextUtils.hasText(baseUrl)) {
            throw new IllegalStateException("须配置 " + BASE_URL_CONFIG);
        }
        return new HttpServiceClient(
                baseUrl,
                endpoint.getServiceAuthToken(),
                properties.getRequestTimeout(),
                objectMapper);
    }

    @Bean
    ConnectionCatalogPort connectionCatalogPort(
            @Qualifier(CLIENT_BEAN) HttpServiceClient dataGeneratorWebClient) {
        return new HttpConnectionCatalogPort(dataGeneratorWebClient);
    }

    @Bean
    JobDefinitionPort jobDefinitionPort(@Qualifier(CLIENT_BEAN) HttpServiceClient dataGeneratorWebClient) {
        return new HttpJobDefinitionPort(dataGeneratorWebClient);
    }

    @Bean
    SchemaCatalogPort schemaCatalogPort(@Qualifier(CLIENT_BEAN) HttpServiceClient dataGeneratorWebClient) {
        return new HttpSchemaCatalogPort(dataGeneratorWebClient);
    }

    @Bean
    JobPreviewPort jobPreviewPort(@Qualifier(CLIENT_BEAN) HttpServiceClient dataGeneratorWebClient) {
        return new HttpJobPreviewPort(dataGeneratorWebClient);
    }

    @Bean
    JobExecutionPort jobExecutionPort(@Qualifier(CLIENT_BEAN) HttpServiceClient dataGeneratorWebClient) {
        return new HttpJobExecutionPort(dataGeneratorWebClient);
    }
}

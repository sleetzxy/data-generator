package com.datagenerator.ai.config;

import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import org.springframework.util.StringUtils;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** dg-web RestTemplate 与 {@link DataGeneratorWebClient} 装配。 */
@Configuration
@ConditionalOnProperty(prefix = "ai", name = {"server", "enabled"}, havingValue = "true")
public class DataGeneratorWebConfiguration {

    public static final String REST_TEMPLATE_BEAN = "dataGeneratorRestTemplate";
    private static final String BASE_URL_CONFIG = "ai.remote-services.data-generator-web.base-url";

    @Bean(name = REST_TEMPLATE_BEAN)
    RestTemplate dataGeneratorRestTemplate(AiProperties properties) {
        Duration timeout = properties.getRequestTimeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    @Bean
    DataGeneratorWebClient dataGeneratorWebClient(
            @Qualifier(REST_TEMPLATE_BEAN) RestTemplate dataGeneratorRestTemplate, AiProperties properties) {
        AiProperties.ServiceEndpoint endpoint = properties.getRemoteServices().getDataGeneratorWeb();
        String baseUrl = endpoint.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("须配置 " + BASE_URL_CONFIG);
        }
        String authToken = endpoint.getServiceAuthToken();
        if (!StringUtils.hasText(authToken)) {
            throw new IllegalStateException(
                    "须配置 ai.remote-services.data-generator-web.service-auth-token"
                            + "（须与 dg-web data-generator.service-auth.token 一致）");
        }
        return new DataGeneratorWebClient(
                dataGeneratorRestTemplate, baseUrl, authToken);
    }
}

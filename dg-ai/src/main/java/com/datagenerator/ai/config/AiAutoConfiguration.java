package com.datagenerator.ai.config;

import com.datagenerator.ai.agent.runtime.AgentRuntime;
import com.datagenerator.ai.agent.runtime.AgentRuntimeRegistry;
import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.application.workflow.AgentExecutionWorkflow;
import com.datagenerator.ai.memory.ChatMemoryStore;
import com.datagenerator.ai.memory.InMemoryChatMemoryStore;
import com.datagenerator.ai.prompt.AgentPrompt;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Configuration
@ConditionalOnProperty(prefix = "ai", name = {"server", "enabled"}, havingValue = "true")
public class AiAutoConfiguration {

    public static final String REST_TEMPLATE_BEAN = "dataGeneratorRestTemplate";
    private static final String BASE_URL_CONFIG = "ai.remote-services.data-generator-web.base-url";

    @Bean
    AgentSessionRegistry agentSessionRegistry() {
        return new AgentSessionRegistry();
    }

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

    @Bean
    AgentPrompt agentPrompt() {
        return new AgentPrompt();
    }

    @Bean
    AgentIoLogger agentIoLogger(AiProperties properties) {
        return new AgentIoLogger(properties);
    }

    @Bean
    AgentRuntimeRegistry agentRuntimeRegistry(List<AgentRuntime> runtimes) {
        return new AgentRuntimeRegistry(runtimes);
    }

    @Bean
    ChatMemoryStore chatMemoryStore(AiProperties properties) {
        return new InMemoryChatMemoryStore(properties);
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor(AiProperties properties) {
        int size = Math.max(1, properties.getAgentThreadPoolSize());
        return Executors.newFixedThreadPool(size);
    }

    @Bean
    AgentExecutionWorkflow agentExecutionWorkflow(
            AgentRuntimeRegistry agentRuntimeRegistry,
            AiProperties aiProperties,
            ChatMemoryStore chatMemoryStore,
            AgentPrompt agentPrompt,
            AgentIoLogger agentIoLogger) {
        return new AgentExecutionWorkflow(
                agentRuntimeRegistry,
                aiProperties,
                chatMemoryStore,
                agentPrompt,
                agentIoLogger);
    }

    @Bean
    AgentSessionApplicationService agentSessionApplicationService(
            AiProperties properties,
            AgentRuntimeRegistry agentRuntimeRegistry,
            ChatMemoryStore chatMemoryStore,
            AgentSessionRegistry sessionRegistry,
            AgentExecutionWorkflow agentExecutionWorkflow) {
        return new AgentSessionApplicationService(
                properties,
                agentRuntimeRegistry,
                chatMemoryStore,
                sessionRegistry,
                agentExecutionWorkflow);
    }
}

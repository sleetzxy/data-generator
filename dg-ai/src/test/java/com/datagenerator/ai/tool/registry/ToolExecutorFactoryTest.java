package com.datagenerator.ai.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.datagenerator.ai.agent.runtime.JobGeneratorMemoryCompressor;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.tool.impl.JobGeneratorTools;
import com.datagenerator.ai.tool.impl.model.DgWebModels.JobDetail;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import com.datagenerator.ai.tool.interceptor.LoggingToolInterceptor;
import com.datagenerator.ai.tool.interceptor.NoOpMetricsToolInterceptor;
import com.datagenerator.ai.tool.interceptor.NoOpRateLimitToolInterceptor;
import com.datagenerator.ai.tool.interceptor.ResilientToolInterceptor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ToolExecutorFactoryTest {

    @Test
    void wrap_getJobYaml_injectsToolMemoryId() {
        AgentSessionRegistry sessionRegistry = new AgentSessionRegistry();
        sessionRegistry.put(new AgentSession(
                "session-1", "job-generator", JobGeneratorToolProvider.TOOL_SET_ID, "deepseek", Instant.now()));
        DataGeneratorWebClient webClient = Mockito.mock(DataGeneratorWebClient.class);
        Mockito.when(webClient.findJob("demo"))
                .thenReturn(new JobDetail("id", "Demo", "demo", "writer:\n  type: csv"));
        JobGeneratorTools tools = new JobGeneratorTools(webClient, sessionRegistry, new JobGeneratorMemoryCompressor());

        Map<ToolSpecification, ToolExecutor> executors = ToolExecutorFactory.wrap(
                tools,
                List.of(
                        new NoOpRateLimitToolInterceptor(),
                        new NoOpMetricsToolInterceptor(),
                        new ResilientToolInterceptor(),
                        new LoggingToolInterceptor(null)));
        ToolExecutor getJobYaml = executors.entrySet().stream()
                .filter(entry -> "getJobYaml".equals(entry.getKey().name()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .name("getJobYaml")
                .arguments("{\"fileName\":\"demo\"}")
                .build();

        String result = getJobYaml.execute(request, "session-1");

        assertThat(result).isNotBlank();
        assertThat(result).contains("dg-ref:demo");
    }
}

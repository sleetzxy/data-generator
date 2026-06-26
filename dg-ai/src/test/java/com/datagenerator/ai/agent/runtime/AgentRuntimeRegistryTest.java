package com.datagenerator.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.config.AiProperties;
import com.datagenerator.ai.tool.impl.JobGeneratorTools;
import com.datagenerator.ai.tool.impl.web.DataGeneratorWebClient;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import com.datagenerator.ai.tool.registry.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeRegistryTest {

    @Test
    void require_unknownAgent_throws() {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of());

        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No runtime registered");
    }

    @Test
    void hasRuntime_registeredAgent_returnsTrue() {
        JobGeneratorToolProvider toolProvider =
                new JobGeneratorToolProvider(mock(JobGeneratorTools.class));
        ToolRegistry toolRegistry = new ToolRegistry(List.of(toolProvider));
        DataGeneratorWebClient webClient = mock(DataGeneratorWebClient.class);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(
                List.of(new JobGeneratorAgentRuntime(
                        toolRegistry,
                        webClient,
                        AgentIoLogger.disabled(),
                        new AiProperties.DraftContinueProperties(),
                        new StreamingHandleRegistry())));

        assertThat(registry.hasRuntime(JobGeneratorAgentRuntime.AGENT_ID)).isTrue();
    }
}

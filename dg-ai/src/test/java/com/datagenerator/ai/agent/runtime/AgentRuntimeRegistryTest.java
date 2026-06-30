package com.datagenerator.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.application.session.AgentSessionRegistry;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
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
        DataGeneratorWebClient webClient = mock(DataGeneratorWebClient.class);
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(
                List.of(new JobGeneratorAgentRuntime(
                        webClient,
                        AgentIoLogger.disabled(),
                        mock(AgentSessionRegistry.class))));

        assertThat(registry.hasRuntime(JobGeneratorAgentRuntime.AGENT_ID)).isTrue();
    }
}

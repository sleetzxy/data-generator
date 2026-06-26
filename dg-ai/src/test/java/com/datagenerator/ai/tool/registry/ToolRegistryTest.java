package com.datagenerator.ai.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.datagenerator.ai.tool.definition.ToolProvider;
import com.datagenerator.ai.tool.provider.JobGeneratorToolProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void require_unknownToolSet_throws() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No ToolProvider registered");
    }

    @Test
    void hasToolSet_registeredToolSet_returnsTrue() {
        ToolProvider provider = mock(ToolProvider.class);
        org.mockito.Mockito.when(provider.toolSetId()).thenReturn(JobGeneratorToolProvider.TOOL_SET_ID);
        ToolRegistry registry = new ToolRegistry(List.of(provider));

        assertThat(registry.hasToolSet(JobGeneratorToolProvider.TOOL_SET_ID)).isTrue();
    }
}

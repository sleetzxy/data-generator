package com.datagenerator.ai.tool.registry;

import com.datagenerator.ai.tool.definition.ToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理各 Tool Set 可用的 {@link ToolProvider}（可被多个 Agent 引用）。 */
public class ToolRegistry {

    private final Map<String, ToolProvider> providers = new LinkedHashMap<>();

    public ToolRegistry(List<ToolProvider> providers) {
        for (ToolProvider provider : providers) {
            ToolProvider previous = this.providers.put(provider.toolSetId(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate ToolProvider for tool set: " + provider.toolSetId());
            }
        }
    }

    public ToolProvider require(String toolSetId) {
        ToolProvider provider = providers.get(toolSetId);
        if (provider == null) {
            throw new IllegalArgumentException("No ToolProvider registered for tool set: " + toolSetId);
        }
        return provider;
    }

    public boolean hasToolSet(String toolSetId) {
        return providers.containsKey(toolSetId);
    }

    public List<String> listToolSetIds() {
        return List.copyOf(providers.keySet());
    }
}

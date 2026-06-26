package com.datagenerator.ai.tool.provider;

import com.datagenerator.ai.application.AgentIoLogger;
import com.datagenerator.ai.tool.definition.JobGeneratorToolSet;
import com.datagenerator.ai.tool.definition.ToolProvider;
import com.datagenerator.ai.tool.impl.JobGeneratorTools;
import com.datagenerator.ai.tool.interceptor.LoggingToolInterceptor;
import com.datagenerator.ai.tool.interceptor.NoOpMetricsToolInterceptor;
import com.datagenerator.ai.tool.interceptor.NoOpRateLimitToolInterceptor;
import com.datagenerator.ai.tool.interceptor.ResilientToolInterceptor;
import com.datagenerator.ai.tool.interceptor.ToolExecutionInterceptor;
import com.datagenerator.ai.tool.registry.ToolExecutorFactory;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.List;
import java.util.Map;

/** Data Generator Job 相关 Tool 组合（当前唯一 Tool Set，含读写能力）。 */
public class JobGeneratorToolProvider implements ToolProvider {

    public static final String TOOL_SET_ID = "job-generator-tools";

    private final JobGeneratorTools tools;

    public JobGeneratorToolProvider(JobGeneratorTools tools) {
        this.tools = tools;
    }

    public JobGeneratorToolSet tools() {
        return tools;
    }

    @Override
    public String toolSetId() {
        return TOOL_SET_ID;
    }

    @Override
    public Map<ToolSpecification, ToolExecutor> createExecutors(AgentIoLogger ioLogger) {
        AgentIoLogger logger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
        List<ToolExecutionInterceptor> interceptors = List.of(
                new NoOpRateLimitToolInterceptor(),
                new NoOpMetricsToolInterceptor(),
                new ResilientToolInterceptor(),
                new LoggingToolInterceptor(logger));
        return ToolExecutorFactory.wrap(tools, interceptors);
    }
}

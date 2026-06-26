package com.datagenerator.ai.tool.interceptor;

import com.datagenerator.ai.application.AgentIoLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 记录 Tool 调用入参与返回摘要。 */
public class LoggingToolInterceptor implements ToolExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolInterceptor.class);

    private final AgentIoLogger ioLogger;

    public LoggingToolInterceptor(AgentIoLogger ioLogger) {
        this.ioLogger = ioLogger != null ? ioLogger : AgentIoLogger.disabled();
    }

    @Override
    public String intercept(ToolExecutionContext context, ToolExecutionDelegate delegate) {
        try {
            String result = delegate.execute();
            ioLogger.logToolCall(context.sessionId(), context.toolName(), context.arguments(), result);
            return result;
        } catch (RuntimeException exception) {
            log.warn(
                    "Tool {} failed for session {}: {}",
                    context.toolName(),
                    context.sessionId(),
                    exception.getMessage());
            throw exception;
        }
    }
}

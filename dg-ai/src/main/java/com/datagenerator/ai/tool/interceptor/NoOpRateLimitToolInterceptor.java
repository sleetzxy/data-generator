package com.datagenerator.ai.tool.interceptor;

/** 限流占位实现，后续可按 session / tool 维度限流。 */
public class NoOpRateLimitToolInterceptor implements ToolExecutionInterceptor {

    @Override
    public String intercept(ToolExecutionContext context, ToolExecutionDelegate delegate) {
        return delegate.execute();
    }
}

package com.datagenerator.ai.tool.interceptor;

/** 指标采集占位实现，后续可接入 Micrometer 等。 */
public class NoOpMetricsToolInterceptor implements ToolExecutionInterceptor {

    @Override
    public String intercept(ToolExecutionContext context, ToolExecutionDelegate delegate) {
        return delegate.execute();
    }
}

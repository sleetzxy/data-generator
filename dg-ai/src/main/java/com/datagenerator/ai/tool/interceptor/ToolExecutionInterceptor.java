package com.datagenerator.ai.tool.interceptor;

/** Tool 调用拦截器，可组合日志、容错、限流等横切逻辑。 */
@FunctionalInterface
public interface ToolExecutionInterceptor {

    String intercept(ToolExecutionContext context, ToolExecutionDelegate delegate);
}

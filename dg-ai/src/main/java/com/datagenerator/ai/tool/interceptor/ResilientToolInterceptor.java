package com.datagenerator.ai.tool.interceptor;

/** 捕获 Tool 参数 JSON 截断异常，返回可读反馈；其余异常向上抛出。 */
public class ResilientToolInterceptor implements ToolExecutionInterceptor {

    @Override
    public String intercept(ToolExecutionContext context, ToolExecutionDelegate delegate) {
        try {
            return delegate.execute();
        } catch (RuntimeException exception) {
            if (ToolArgumentErrors.isTruncatedArguments(exception)) {
                return ToolArgumentErrors.TRUNCATED_ARGS_FEEDBACK;
            }
            throw exception;
        }
    }
}

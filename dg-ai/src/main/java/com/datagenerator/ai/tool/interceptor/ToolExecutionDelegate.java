package com.datagenerator.ai.tool.interceptor;

/** Tool 执行委托。 */
@FunctionalInterface
public interface ToolExecutionDelegate {

    String execute();
}

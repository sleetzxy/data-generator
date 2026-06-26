package com.datagenerator.ai.tool.registry;

import com.datagenerator.ai.tool.interceptor.ToolExecutionContext;
import com.datagenerator.ai.tool.interceptor.ToolExecutionDelegate;
import com.datagenerator.ai.tool.interceptor.ToolExecutionInterceptor;
import com.datagenerator.ai.tool.interceptor.ToolResultTexts;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Tool 实现对象包装为带拦截器链的 LangChain4j {@link ToolExecutor}。 */
public final class ToolExecutorFactory {

    private ToolExecutorFactory() {
    }

    public static Map<ToolSpecification, ToolExecutor> wrap(
            Object toolObject, List<ToolExecutionInterceptor> interceptors) {
        List<ToolExecutionInterceptor> chain =
                interceptors != null ? List.copyOf(interceptors) : List.of();
        List<Method> toolMethods = findToolMethods(toolObject.getClass());
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (Method method : toolMethods) {
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            DefaultToolExecutor delegate = new DefaultToolExecutor(toolObject, method);
            executors.put(specification, createExecutor(delegate, chain));
        }
        return executors;
    }

    private static ToolExecutor createExecutor(
            DefaultToolExecutor delegate, List<ToolExecutionInterceptor> interceptors) {
        return (request, memoryId) -> {
            String sessionId = memoryId != null ? String.valueOf(memoryId) : "unknown";
            String toolName = request != null ? request.name() : "unknown";
            String arguments = request != null ? request.arguments() : null;
            ToolExecutionContext context =
                    new ToolExecutionContext(sessionId, toolName, arguments, request, memoryId);
            ToolExecutionDelegate execution = () -> {
                String result = delegate.execute(request, memoryId);
                return ToolResultTexts.ensureNonBlank(result, toolName);
            };
            return applyInterceptors(interceptors, context, execution);
        };
    }

    private static String applyInterceptors(
            List<ToolExecutionInterceptor> interceptors,
            ToolExecutionContext context,
            ToolExecutionDelegate delegate) {
        ToolExecutionDelegate next = delegate;
        for (int index = interceptors.size() - 1; index >= 0; index--) {
            ToolExecutionInterceptor interceptor = interceptors.get(index);
            ToolExecutionDelegate inner = next;
            next = () -> interceptor.intercept(context, inner);
        }
        return next.execute();
    }

    private static List<Method> findToolMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                methods.add(method);
            }
        }
        return methods;
    }
}

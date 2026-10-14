package com.datagenerator.core.expression;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

/**
 * 以 Map 为 SpEL 根对象时，支持 {@code key} 形式的属性读取（替代 spring-context 的 MapAccessor）。
 */
final class MapRootPropertyAccessor implements PropertyAccessor {

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[] {Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map<?, ?>;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) target;
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) {
        throw new UnsupportedOperationException("Map 根对象只读，不支持 SpEL 写入");
    }
}

package com.datagenerator.core.expression;

import com.datagenerator.spi.expression.ExpressionEvaluator;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpelExpressionEvaluator implements ExpressionEvaluator {

    private static final MapAccessor MAP_ACCESSOR = new MapAccessor();

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> compiledExpressions = new ConcurrentHashMap<>();
    private final ThreadLocal<StandardEvaluationContext> evaluationContext =
            ThreadLocal.withInitial(() -> {
                StandardEvaluationContext context = new StandardEvaluationContext();
                context.addPropertyAccessor(MAP_ACCESSOR);
                return context;
            });

    @Override
    public String language() {
        return "spel";
    }

    @Override
    public String type() {
        return "spel";
    }

    @Override
    public Object evaluate(String expr, Map<String, Object> bindings) {
        Expression compiled = compiledExpressions.computeIfAbsent(expr, parser::parseExpression);
        StandardEvaluationContext context = evaluationContext.get();
        context.setRootObject(bindings);
        return compiled.getValue(context);
    }
}

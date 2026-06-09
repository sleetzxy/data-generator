package com.datagenerator.core.generator;

import com.datagenerator.core.expression.ExpressionEvaluatorRegistry;
import com.datagenerator.spi.expression.ExpressionEvaluator;
import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;

/**
 * 基于 SpEL / Aviator / Groovy 表达式，从当前行已生成字段计算列值。
 */
public class ExpressionGenerator extends AbstractValueGenerator {

    private final ExpressionEvaluatorRegistry expressionRegistry;

    public ExpressionGenerator() {
        this(new ExpressionEvaluatorRegistry());
    }

    ExpressionGenerator(ExpressionEvaluatorRegistry expressionRegistry) {
        super("expression");
        this.expressionRegistry = expressionRegistry;
    }

    @Override
    public Object generate(GenerationContext ctx, Map<String, Object> config) {
        Object expressionValue = config.get("expression");
        if (expressionValue == null || String.valueOf(expressionValue).isBlank()) {
            throw new IllegalArgumentException("expression generator requires 'expression'");
        }
        String language = String.valueOf(config.getOrDefault("language", "spel"));
        ExpressionEvaluator evaluator = expressionRegistry.get(language);
        return evaluator.evaluate(String.valueOf(expressionValue), ctx.rowBeingBuilt().getFields());
    }
}

package com.datagenerator.core.expression;

import com.datagenerator.spi.expression.ExpressionEvaluator;

import java.util.HashMap;
import java.util.Map;

public class ExpressionEvaluatorRegistry {

    private final Map<String, ExpressionEvaluator> evaluators = new HashMap<>();

    public ExpressionEvaluatorRegistry() {
        register(new SpelExpressionEvaluator());
        register(new AviatorExpressionEvaluator());
        register(new GroovyExpressionEvaluator());
    }

    public void register(ExpressionEvaluator evaluator) {
        evaluators.put(evaluator.language().toLowerCase(), evaluator);
    }

    public ExpressionEvaluator get(String language) {
        if (language == null || language.isBlank()) {
            return evaluators.get("spel");
        }
        ExpressionEvaluator evaluator = evaluators.get(language.toLowerCase());
        if (evaluator == null) {
            throw new IllegalArgumentException("Unsupported expression language: " + language);
        }
        return evaluator;
    }
}

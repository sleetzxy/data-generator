package com.datagenerator.core.expression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyExpressionEvaluatorTest {

    @Test
    void groovyExpressionEvaluator_evaluatesBooleanExpression() {
        GroovyExpressionEvaluator evaluator = new GroovyExpressionEvaluator();
        Object result = evaluator.evaluate("amount > discount", Map.of("amount", 100, "discount", 50));
        assertThat(result).isEqualTo(true);
    }
}

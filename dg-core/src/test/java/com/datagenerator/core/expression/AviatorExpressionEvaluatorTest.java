package com.datagenerator.core.expression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AviatorExpressionEvaluatorTest {

    @Test
    void aviatorExpressionEvaluator_evaluatesBooleanExpression() {
        AviatorExpressionEvaluator evaluator = new AviatorExpressionEvaluator();
        Object result = evaluator.evaluate("amount > discount * 1.1", Map.of("amount", 100, "discount", 50));
        assertThat(result).isEqualTo(true);
    }
}

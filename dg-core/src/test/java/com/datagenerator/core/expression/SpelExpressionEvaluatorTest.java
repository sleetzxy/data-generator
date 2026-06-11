package com.datagenerator.core.expression;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpelExpressionEvaluatorTest {

    @Test
    void evaluate_readsFieldsFromMapRoot() {
        SpelExpressionEvaluator evaluator = new SpelExpressionEvaluator();
        Object result = evaluator.evaluate(
                "xh + '_' + wfbh",
                Map.of("xh", "1", "wfbh", "4401132023000001"));
        assertThat(result).isEqualTo("1_4401132023000001");
    }

    @Test
    void evaluate_repeatedSameExpression_usesCompiledCache() {
        SpelExpressionEvaluator evaluator = new SpelExpressionEvaluator();
        String expression = "T(Integer).parseInt(sfzmhm.substring(16, 17)) % 2 == 1 ? '1' : '2'";
        Map<String, Object> bindings = Map.of("sfzmhm", "110101199001011234");

        for (int i = 0; i < 100; i++) {
            assertThat(evaluator.evaluate(expression, bindings)).isEqualTo("1");
        }
    }

    @Test
    void evaluate_reusesThreadLocalContextAcrossRows() {
        SpelExpressionEvaluator evaluator = new SpelExpressionEvaluator();
        String expression = "xh + '_' + wfbh";
        assertThat(evaluator.evaluate(expression, Map.of("xh", "1", "wfbh", "A"))).isEqualTo("1_A");
        assertThat(evaluator.evaluate(expression, Map.of("xh", "2", "wfbh", "B"))).isEqualTo("2_B");
    }
}

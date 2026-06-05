package com.datagenerator.core.expression;

import com.datagenerator.spi.expression.ExpressionEvaluator;
import com.googlecode.aviator.AviatorEvaluator;

import java.util.Map;

public class AviatorExpressionEvaluator implements ExpressionEvaluator {

    @Override
    public String language() {
        return "aviator";
    }

    @Override
    public String type() {
        return "aviator";
    }

    @Override
    public Object evaluate(String expr, Map<String, Object> bindings) {
        return AviatorEvaluator.execute(expr, bindings, true);
    }
}

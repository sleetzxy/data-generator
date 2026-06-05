package com.datagenerator.core.expression;

import com.datagenerator.spi.expression.ExpressionEvaluator;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

public class SpelExpressionEvaluator implements ExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

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
        StandardEvaluationContext context = new StandardEvaluationContext(bindings);
        context.addPropertyAccessor(new MapAccessor());
        return parser.parseExpression(expr).getValue(context);
    }
}

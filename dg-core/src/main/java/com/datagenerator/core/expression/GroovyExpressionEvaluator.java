package com.datagenerator.core.expression;

import com.datagenerator.spi.expression.ExpressionEvaluator;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;

import java.util.Map;

public class GroovyExpressionEvaluator implements ExpressionEvaluator {

    @Override
    public String language() {
        return "groovy";
    }

    @Override
    public String type() {
        return "groovy";
    }

    @Override
    public Object evaluate(String expr, Map<String, Object> bindings) {
        Binding binding = new Binding();
        bindings.forEach(binding::setVariable);
        return new GroovyShell(binding).evaluate(expr);
    }
}

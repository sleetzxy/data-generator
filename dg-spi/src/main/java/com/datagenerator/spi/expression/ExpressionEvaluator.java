package com.datagenerator.spi.expression;

import com.datagenerator.spi.Plugin;

import java.util.Map;

/**
 * Evaluates expressions in a supported language.
 */
public interface ExpressionEvaluator extends Plugin {

    String language();

    Object evaluate(String expr, Map<String, Object> bindings);
}

package com.datagenerator.core.constraint.composite;

import com.datagenerator.core.expression.ExpressionEvaluatorRegistry;
import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.expression.ExpressionEvaluator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.HashMap;
import java.util.Map;

/**
 * 自定义表达式约束（level=custom），支持 SpEL / Aviator。
 */
public class ExpressionConstraintValidator implements ConstraintValidator {

    private final ExpressionEvaluatorRegistry evaluatorRegistry;

    public ExpressionConstraintValidator(ExpressionEvaluatorRegistry evaluatorRegistry) {
        this.evaluatorRegistry = evaluatorRegistry;
    }

    @Override
    public String type() {
        return "expression";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String expression = String.valueOf(ruleConfig.get("expression"));
        String language = ruleConfig.get("language") == null ? "spel" : String.valueOf(ruleConfig.get("language"));
        ExpressionEvaluator evaluator = evaluatorRegistry.get(language);

        Map<String, Object> bindings = new HashMap<>(ctx.currentRow().getFields());
        if (ctx.bindings() != null) {
            bindings.putAll(ctx.bindings());
        }

        Object result = evaluator.evaluate(expression, bindings);
        if (result instanceof Boolean booleanResult) {
            return booleanResult
                    ? ConstraintResult.valid()
                    : ConstraintResult.invalid("Expression constraint failed: " + expression);
        }
        return ConstraintResult.invalid("Expression did not evaluate to boolean: " + expression);
    }
}

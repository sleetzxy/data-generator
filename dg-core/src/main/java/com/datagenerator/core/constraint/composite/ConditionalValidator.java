package com.datagenerator.core.constraint.composite;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.expression.ExpressionEvaluator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.HashMap;
import java.util.Map;

public class ConditionalValidator implements ConstraintValidator {

    private final ExpressionEvaluator expressionEvaluator;

    public ConditionalValidator(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public String type() {
        return "conditional";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String expression = (String) ruleConfig.get("expression");
        String language = (String) ruleConfig.get("language");
        if (language != null && !language.equalsIgnoreCase(expressionEvaluator.language())) {
            return ConstraintResult.invalid("Unsupported expression language: " + language);
        }

        Map<String, Object> bindings = new HashMap<>(ctx.currentRow().getFields());
        if (ctx.bindings() != null) {
            bindings.putAll(ctx.bindings());
        }

        Object result = expressionEvaluator.evaluate(expression, bindings);
        if (result instanceof Boolean booleanResult) {
            return booleanResult
                    ? ConstraintResult.valid()
                    : ConstraintResult.invalid("Conditional expression failed: " + expression);
        }
        return ConstraintResult.invalid("Conditional expression did not evaluate to boolean: " + expression);
    }
}

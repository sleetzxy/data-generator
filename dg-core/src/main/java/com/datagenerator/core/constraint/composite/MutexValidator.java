package com.datagenerator.core.constraint.composite;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.List;
import java.util.Map;

public class MutexValidator implements ConstraintValidator {

    @Override
    public String type() {
        return "mutex";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String rule = (String) ruleConfig.get("rule");
        List<String> fields = (List<String>) ruleConfig.get("fields");

        if (!"at_least_one".equals(rule)) {
            return ConstraintResult.invalid("Unsupported mutex rule: " + rule);
        }
        if (fields == null || fields.isEmpty()) {
            return ConstraintResult.invalid("Mutex constraint requires at least one field");
        }

        boolean anyPresent = fields.stream()
                .map(field -> ctx.currentRow().get(field))
                .anyMatch(this::isPresent);
        if (anyPresent) {
            return ConstraintResult.valid();
        }
        return ConstraintResult.invalid("At least one of " + fields + " must be present");
    }

    private boolean isPresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String stringValue) {
            return !stringValue.isBlank();
        }
        return true;
    }
}

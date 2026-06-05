package com.datagenerator.core.constraint.field;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.Map;
import java.util.Optional;

public class RangeValidator implements ConstraintValidator {

    @Override
    public String type() {
        return "range";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String field = (String) ruleConfig.get("field");
        Object value = ctx.currentRow().get(field);
        if (value == null) {
            return ConstraintResult.valid();
        }

        double numericValue = toDouble(value);
        Double min = toDoubleOrNull(ruleConfig.get("min"));
        Double max = toDoubleOrNull(ruleConfig.get("max"));

        if (min != null && numericValue < min) {
            return ConstraintResult.invalid(
                    "Field '" + field + "' value " + numericValue + " is below minimum " + min);
        }
        if (max != null && numericValue > max) {
            return ConstraintResult.invalid(
                    "Field '" + field + "' value " + numericValue + " exceeds maximum " + max);
        }
        return ConstraintResult.valid();
    }

    @Override
    public Optional<Object> repair(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String field = (String) ruleConfig.get("field");
        Object value = ctx.currentRow().get(field);
        if (value == null) {
            return Optional.empty();
        }
        double numericValue = toDouble(value);
        Double min = toDoubleOrNull(ruleConfig.get("min"));
        Double max = toDoubleOrNull(ruleConfig.get("max"));
        if (min != null && numericValue < min) {
            return Optional.of(min);
        }
        if (max != null && numericValue > max) {
            return Optional.of(max);
        }
        return Optional.of(value);
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private static Double toDoubleOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
}

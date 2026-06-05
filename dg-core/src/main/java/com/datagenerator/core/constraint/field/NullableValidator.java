package com.datagenerator.core.constraint.field;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.Map;

public class NullableValidator implements ConstraintValidator {

    @Override
    public String type() {
        return "nullable";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String field = (String) ruleConfig.get("field");
        Object value = ctx.currentRow().get(field);
        if (value != null) {
            return ConstraintResult.valid();
        }
        return ConstraintResult.invalid("Field '" + field + "' must not be null");
    }
}

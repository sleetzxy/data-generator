package com.datagenerator.core.constraint;

import com.datagenerator.core.constraint.composite.ConditionalValidator;
import com.datagenerator.core.constraint.composite.MutexValidator;
import com.datagenerator.core.constraint.field.ForeignKeyValidator;
import com.datagenerator.core.constraint.field.NullableValidator;
import com.datagenerator.core.constraint.field.RangeValidator;
import com.datagenerator.core.expression.SpelExpressionEvaluator;
import com.datagenerator.spi.constraint.ConstraintValidator;

import java.util.HashMap;
import java.util.Map;

public class ConstraintValidatorRegistry {

    private final Map<String, ConstraintValidator> validators = new HashMap<>();

    public ConstraintValidatorRegistry() {
        register(new RangeValidator());
        register(new NullableValidator());
        register(new ForeignKeyValidator());
        register(new ConditionalValidator(new SpelExpressionEvaluator()));
        register(new MutexValidator());
    }

    public void register(ConstraintValidator validator) {
        validators.put(validator.type(), validator);
    }

    public ConstraintValidator get(String type) {
        ConstraintValidator validator = validators.get(type);
        if (validator == null) {
            throw new IllegalArgumentException("Unknown constraint type: " + type);
        }
        return validator;
    }
}

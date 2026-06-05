package com.datagenerator.core.constraint;

import com.datagenerator.core.constraint.composite.ConditionalValidator;
import com.datagenerator.core.constraint.composite.ExpressionConstraintValidator;
import com.datagenerator.core.constraint.composite.MutexValidator;
import com.datagenerator.core.constraint.field.ForeignKeyValidator;
import com.datagenerator.core.constraint.field.NullableValidator;
import com.datagenerator.core.constraint.field.RangeValidator;
import com.datagenerator.core.constraint.spatial.WithinSpatialValidator;
import com.datagenerator.core.expression.ExpressionEvaluatorRegistry;
import com.datagenerator.spi.constraint.ConstraintValidator;

import java.util.HashMap;
import java.util.Map;

public class ConstraintValidatorRegistry {

    private final Map<String, ConstraintValidator> validators = new HashMap<>();

    public ConstraintValidatorRegistry() {
        this(new ExpressionEvaluatorRegistry());
    }

    public ConstraintValidatorRegistry(ExpressionEvaluatorRegistry expressionRegistry) {
        register(new RangeValidator());
        register(new NullableValidator());
        register(new ForeignKeyValidator());
        register(new ConditionalValidator(expressionRegistry));
        register(new MutexValidator());
        register(new ExpressionConstraintValidator(expressionRegistry));
        register(new WithinSpatialValidator());
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

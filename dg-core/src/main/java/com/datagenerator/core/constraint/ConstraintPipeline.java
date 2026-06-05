package com.datagenerator.core.constraint;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.List;
import java.util.Map;

public class ConstraintPipeline {

    private final ConstraintValidatorRegistry registry;
    private final List<ConstraintDefinition> constraints;

    public ConstraintPipeline(List<ConstraintDefinition> constraints) {
        this(constraints, new ConstraintValidatorRegistry());
    }

    public ConstraintPipeline(List<ConstraintDefinition> constraints, ConstraintValidatorRegistry registry) {
        this.constraints = List.copyOf(constraints);
        this.registry = registry;
    }

    public ConstraintResult validate(ConstraintContext ctx) {
        for (String level : List.of("field", "composite")) {
            ConstraintResult result = validateLevel(ctx, level);
            if (!result.isValid()) {
                return result;
            }
        }
        return ConstraintResult.valid();
    }

    private ConstraintResult validateLevel(ConstraintContext ctx, String level) {
        for (ConstraintDefinition definition : constraints) {
            if (!level.equals(definition.getLevel())) {
                continue;
            }
            ConstraintResult result = validateOne(ctx, definition);
            if (!result.isValid()) {
                return result;
            }
        }
        return ConstraintResult.valid();
    }

    private ConstraintResult validateOne(ConstraintContext ctx, ConstraintDefinition definition) {
        String onFail = definition.getOnFail();
        if (onFail != null && !onFail.isBlank() && !"reject".equalsIgnoreCase(onFail)) {
            throw new IllegalArgumentException("Unsupported on_fail strategy: " + onFail);
        }

        ConstraintValidator validator = registry.get(definition.getType());
        Map<String, Object> ruleConfig = ConstraintRuleMapper.toRuleConfig(definition);
        return validator.validate(ctx, ruleConfig);
    }

}

package com.datagenerator.spi.constraint;

import com.datagenerator.spi.Plugin;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;

import java.util.Map;
import java.util.Optional;

/**
 * Validates (and optionally repairs) row values against a rule.
 */
public interface ConstraintValidator extends Plugin {

    ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig);

    default Optional<Object> repair(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        return Optional.empty();
    }
}

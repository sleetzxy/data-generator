package com.datagenerator.core.constraint;

import com.datagenerator.core.schema.ConstraintDefinition;
import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;
import com.datagenerator.spi.model.DataRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConstraintPipeline {

    private static final Logger log = LoggerFactory.getLogger(ConstraintPipeline.class);

    private final ConstraintValidatorRegistry registry;
    private final List<ConstraintDefinition> constraints;
    private final String defaultOnFail;

    public ConstraintPipeline(List<ConstraintDefinition> constraints) {
        this(constraints, new ConstraintValidatorRegistry(), "reject");
    }

    public ConstraintPipeline(List<ConstraintDefinition> constraints, ConstraintValidatorRegistry registry) {
        this(constraints, registry, "reject");
    }

    public ConstraintPipeline(
            List<ConstraintDefinition> constraints,
            ConstraintValidatorRegistry registry,
            String defaultOnFail) {
        this.constraints = List.copyOf(constraints);
        this.registry = registry;
        this.defaultOnFail = defaultOnFail == null ? "reject" : defaultOnFail;
    }

    public ConstraintValidationOutcome validateRow(ConstraintContext ctx) {
        DataRow row = ctx.currentRow();
        List<String> warnings = new ArrayList<>();

        for (String level : List.of("field", "composite", "spatial", "custom")) {
            for (ConstraintDefinition definition : constraints) {
                if (!level.equals(definition.getLevel())) {
                    continue;
                }
                ConstraintValidationOutcome outcome = validateOne(ctx, row, definition, warnings);
                if (!outcome.isAccepted()) {
                    return outcome;
                }
                row = outcome.row();
                warnings = new ArrayList<>(outcome.warnings());
                ctx = new ConstraintContext(row, ctx.upstreamTables(), ctx.bindings());
            }
        }
        return ConstraintValidationOutcome.accepted(row, warnings);
    }

    /** @deprecated 使用 {@link #validateRow(ConstraintContext)} */
    @Deprecated
    public ConstraintResult validate(ConstraintContext ctx) {
        ConstraintValidationOutcome outcome = validateRow(ctx);
        return outcome.isAccepted()
                ? ConstraintResult.valid()
                : ConstraintResult.invalid(outcome.rejectReason());
    }

    private ConstraintValidationOutcome validateOne(
            ConstraintContext ctx,
            DataRow row,
            ConstraintDefinition definition,
            List<String> warnings) {
        String onFail = resolveOnFail(definition);
        ConstraintValidator validator = registry.get(resolveValidatorType(definition));
        Map<String, Object> ruleConfig = ConstraintRuleMapper.toRuleConfig(definition);
        ConstraintContext rowContext = new ConstraintContext(row, ctx.upstreamTables(), ctx.bindings());
        ConstraintResult result = validator.validate(rowContext, ruleConfig);

        if (result.isValid()) {
            return ConstraintValidationOutcome.accepted(row, warnings);
        }

        return switch (onFail.toLowerCase()) {
            case "repair" -> applyRepair(validator, rowContext, ruleConfig, warnings, result.message());
            case "warn" -> {
                String message = result.message() == null ? "constraint warning" : result.message();
                log.warn("Constraint warn [{}]: {}", definition.getType(), message);
                warnings.add(message);
                yield ConstraintValidationOutcome.accepted(row, warnings);
            }
            default -> ConstraintValidationOutcome.rejected(result.message());
        };
    }

    private ConstraintValidationOutcome applyRepair(
            ConstraintValidator validator,
            ConstraintContext ctx,
            Map<String, Object> ruleConfig,
            List<String> warnings,
            String failureMessage) {
        Optional<Object> repaired = validator.repair(ctx, ruleConfig);
        if (repaired.isEmpty()) {
            return ConstraintValidationOutcome.rejected(failureMessage);
        }
        String field = ruleConfig.get("field") == null ? null : String.valueOf(ruleConfig.get("field"));
        DataRow row = ctx.currentRow();
        if (field != null) {
            row.set(field, repaired.get());
        }
        log.debug("Constraint repaired field '{}': {}", field, repaired.get());
        return ConstraintValidationOutcome.accepted(row, warnings);
    }

    private String resolveOnFail(ConstraintDefinition definition) {
        if (definition.getOnFail() != null && !definition.getOnFail().isBlank()) {
            return definition.getOnFail();
        }
        return defaultOnFail;
    }

    private static String resolveValidatorType(ConstraintDefinition definition) {
        if ("custom".equalsIgnoreCase(definition.getLevel())) {
            return "expression";
        }
        return definition.getType();
    }
}

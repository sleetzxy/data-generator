package com.datagenerator.core.constraint.field;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;
import com.datagenerator.spi.model.DataRow;

import java.util.List;
import java.util.Map;

public class ForeignKeyValidator implements ConstraintValidator {

    @Override
    public String type() {
        return "foreign_key";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String field = (String) ruleConfig.get("field");
        String refTable = (String) ruleConfig.get("ref_table");
        String refField = (String) ruleConfig.get("ref_field");
        Object value = ctx.currentRow().get(field);

        if (value == null) {
            return ConstraintResult.valid();
        }

        Object binding = ctx.bindings().get(ForeignKeyIndex.BINDING_KEY);
        if (binding instanceof ForeignKeyIndex index) {
            if (index.contains(refTable, refField, value)) {
                return ConstraintResult.valid();
            }
            return ConstraintResult.invalid(
                    "Field '" + field + "' value " + value + " not found in " + refTable + "." + refField);
        }

        List<DataRow> upstreamRows = ctx.upstreamTables().get(refTable);
        if (upstreamRows == null || upstreamRows.isEmpty()) {
            return ConstraintResult.invalid(
                    "Foreign key reference table '" + refTable + "' has no upstream data");
        }

        boolean found = upstreamRows.stream()
                .anyMatch(row -> value.equals(row.get(refField)));
        if (!found) {
            return ConstraintResult.invalid(
                    "Field '" + field + "' value " + value + " not found in " + refTable + "." + refField);
        }
        return ConstraintResult.valid();
    }
}

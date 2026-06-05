package com.datagenerator.core.constraint;

import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.DataRow;

import java.util.ArrayList;
import java.util.List;

/**
 * 单行约束校验结果，含 repair/warn 产生的告警。
 */
public final class ConstraintValidationOutcome {

    private final boolean accepted;
    private final DataRow row;
    private final String rejectReason;
    private final List<String> warnings;

    private ConstraintValidationOutcome(
            boolean accepted,
            DataRow row,
            String rejectReason,
            List<String> warnings) {
        this.accepted = accepted;
        this.row = row;
        this.rejectReason = rejectReason;
        this.warnings = List.copyOf(warnings);
    }

    public static ConstraintValidationOutcome accepted(DataRow row) {
        return new ConstraintValidationOutcome(true, row, null, List.of());
    }

    public static ConstraintValidationOutcome accepted(DataRow row, List<String> warnings) {
        return new ConstraintValidationOutcome(true, row, null, warnings);
    }

    public static ConstraintValidationOutcome rejected(String reason) {
        return new ConstraintValidationOutcome(false, null, reason, List.of());
    }

    public boolean isAccepted() {
        return accepted;
    }

    public DataRow row() {
        return row;
    }

    public String rejectReason() {
        return rejectReason;
    }

    public List<String> warnings() {
        return warnings;
    }
}

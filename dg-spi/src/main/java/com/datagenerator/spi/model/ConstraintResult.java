package com.datagenerator.spi.model;

import java.util.Objects;

/**
 * Outcome of a constraint validation.
 */
public final class ConstraintResult {

    private final boolean valid;
    private final String message;
    private final Object repairedValue;

    private ConstraintResult(boolean valid, String message, Object repairedValue) {
        this.valid = valid;
        this.message = message;
        this.repairedValue = repairedValue;
    }

    public static ConstraintResult valid() {
        return new ConstraintResult(true, null, null);
    }

    public static ConstraintResult invalid(String message) {
        return new ConstraintResult(false, message, null);
    }

    public static ConstraintResult repaired(Object repairedValue) {
        return new ConstraintResult(true, null, repairedValue);
    }

    public boolean isValid() {
        return valid;
    }

    public String message() {
        return message;
    }

    public Object repairedValue() {
        return repairedValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConstraintResult that)) {
            return false;
        }
        return valid == that.valid
                && Objects.equals(message, that.message)
                && Objects.equals(repairedValue, that.repairedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, message, repairedValue);
    }

    @Override
    public String toString() {
        return "ConstraintResult[valid=" + valid + ", message=" + message + ", repairedValue=" + repairedValue + ']';
    }
}

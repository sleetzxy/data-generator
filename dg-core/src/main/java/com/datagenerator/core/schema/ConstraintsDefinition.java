package com.datagenerator.core.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConstraintsDefinition {

    private List<ConstraintDefinition> constraints = new ArrayList<>();

    public List<ConstraintDefinition> getConstraints() {
        return Collections.unmodifiableList(constraints);
    }

    public void setConstraints(List<ConstraintDefinition> constraints) {
        this.constraints = constraints == null ? new ArrayList<>() : new ArrayList<>(constraints);
    }
}

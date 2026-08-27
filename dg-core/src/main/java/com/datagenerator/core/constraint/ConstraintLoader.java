package com.datagenerator.core.constraint;

import com.datagenerator.core.model.ConstraintDefinition;
import com.datagenerator.core.model.ConstraintsDefinition;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.TableSchema;
import com.datagenerator.core.model.TableTask;
import com.datagenerator.core.model.YamlConfigLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConstraintLoader {

    private final YamlConfigLoader configLoader;

    public ConstraintLoader(YamlConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    public List<ConstraintDefinition> load(TableSchema schema, TaskConfig taskConfig, TableTask tableTask) {
        List<ConstraintDefinition> schemaConstraints = loadPath(schema == null ? null : schema.getConstraints());
        List<ConstraintDefinition> taskConfigConstraints = loadTaskConfigConstraints(taskConfig);
        List<ConstraintDefinition> tableConstraints = loadTableConstraints(tableTask);
        return merge(schemaConstraints, taskConfigConstraints, tableConstraints);
    }

    private List<ConstraintDefinition> loadTaskConfigConstraints(TaskConfig taskConfig) {
        if (taskConfig == null) {
            return List.of();
        }
        if (!taskConfig.getInlineConstraints().isEmpty()) {
            return new ArrayList<>(taskConfig.getInlineConstraints());
        }
        return loadPath(taskConfig.getConstraints());
    }

    private List<ConstraintDefinition> loadTableConstraints(TableTask tableTask) {
        if (tableTask == null) {
            return List.of();
        }
        if (!tableTask.getInlineConstraints().isEmpty()) {
            return new ArrayList<>(tableTask.getInlineConstraints());
        }
        return loadPath(tableTask.getConstraints());
    }

    public static List<ConstraintDefinition> merge(
            List<ConstraintDefinition> schemaLevel,
            List<ConstraintDefinition> taskConfigLevel,
            List<ConstraintDefinition> tableLevel) {
        Map<String, ConstraintDefinition> merged = new LinkedHashMap<>();

        for (ConstraintDefinition constraint : schemaLevel) {
            merged.put(constraintKey(constraint), constraint);
        }
        for (ConstraintDefinition constraint : taskConfigLevel) {
            merged.put(constraintKey(constraint), constraint);
        }
        for (ConstraintDefinition constraint : tableLevel) {
            merged.put(constraintKey(constraint), constraint);
        }

        return new ArrayList<>(merged.values());
    }

    static String constraintKey(ConstraintDefinition constraint) {
        String level = constraint.getLevel() == null ? "" : constraint.getLevel();
        if ("field".equals(level)) {
            return "field:" + constraint.getField() + ":" + constraint.getType();
        }
        if ("composite".equals(level)) {
            if ("conditional".equals(constraint.getType())) {
                return "composite:conditional:" + constraint.getExpression();
            }
            if ("mutex".equals(constraint.getType())) {
                return "composite:mutex:" + String.join(",", constraint.getFields());
            }
        }
        return level + ":" + constraint.getType() + ":" + constraint.getField();
    }

    private List<ConstraintDefinition> loadPath(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        ConstraintsDefinition definition = configLoader.loadConstraints(path);
        return new ArrayList<>(definition.getConstraints());
    }
}

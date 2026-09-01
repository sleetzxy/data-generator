package com.datagenerator.core.model;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.datagenerator.core.config.WriterConfigResolver;

public class YamlConfigLoader {

    private final ConfigPathResolver pathResolver;

    public YamlConfigLoader(ConfigPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public TableSchema loadSchema(String path) {
        return toSchemaDefinition(loadYamlMap(path));
    }

    public TableSchema toSchemaDefinition(Map<String, Object> root) {
        return YamlMappingUtils.toSchemaDefinition(root);
    }

    public TaskConfig loadTaskConfig(String path) {
        return loadTaskConfigFromRoot(loadYamlMap(path));
    }

    public TaskConfig loadTaskConfigFromContent(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new ConfigLoadException("Empty YAML content");
        }
        try {
            Object loaded = new Yaml().load(yamlContent);
            if (loaded == null) {
                throw new ConfigLoadException("Empty YAML content");
            }
            TaskConfig taskConfig = loadTaskConfigFromRoot(YamlMappingUtils.asMap(loaded));
            validateTaskConfigHasTables(taskConfig);
            return taskConfig;
        } catch (ConfigLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConfigLoadException("Failed to parse YAML content", exception);
        }
    }

    private void validateTaskConfigHasTables(TaskConfig taskConfig) {
        if (taskConfig.getTables() == null || taskConfig.getTables().isEmpty()) {
            throw new ConfigLoadException("Task config must define at least one table in 'tables'");
        }
    }

    private TaskConfig loadTaskConfigFromRoot(Map<String, Object> root) {
        TaskConfig taskConfig = new TaskConfig();
        String name = YamlMappingUtils.asString(root.get("name"));
        if (name == null || name.isBlank()) {
            throw new ConfigLoadException("Task config must define 'name'");
        }
        String id = YamlMappingUtils.asString(root.get("id"));
        if (id == null || id.isBlank()) {
            throw new ConfigLoadException("Task config must define 'id'");
        }
        taskConfig.setName(name);
        taskConfig.setId(id);
        Object constraintsValue = root.get("constraints");
        if (constraintsValue instanceof List<?>) {
            taskConfig.setInlineConstraints(YamlMappingUtils.toConstraintDefinitions(constraintsValue));
        } else {
            taskConfig.setConstraints(YamlMappingUtils.asString(constraintsValue));
        }
        taskConfig.setWriter(YamlMappingUtils.asMap(root.get("writer")));
        taskConfig.setWriters(YamlMappingUtils.asMapList(root.get("writers")));
        taskConfig.setConnections(YamlMappingUtils.asNamedConnectionMap(root.get("connections")));
        WriterConfigResolver.validateTaskConfigWriters(taskConfig);

        List<SeedDefinition> seeds = new ArrayList<>();
        for (Map<String, Object> seedSource : YamlMappingUtils.asMapList(root.get("seeds"))) {
            seeds.add(YamlMappingUtils.toSeedDefinition(seedSource));
        }
        taskConfig.setSeeds(seeds);

        List<TableTask> tables = new ArrayList<>();
        for (Map<String, Object> tableSource : YamlMappingUtils.asMapList(root.get("tables"))) {
            tables.add(YamlMappingUtils.toTableTask(tableSource));
        }
        taskConfig.setTables(tables);

        Object scheduleValue = root.get("schedule");
        if (scheduleValue != null) {
            taskConfig.setSchedule(YamlMappingUtils.toScheduleDefinition(YamlMappingUtils.asMap(scheduleValue)));
        }
        TaskSeedValidator.validate(taskConfig, this);
        return taskConfig;
    }

    public ConstraintsDefinition loadConstraints(String path) {
        Map<String, Object> root = loadYamlMap(path);
        ConstraintsDefinition constraintsDefinition = new ConstraintsDefinition();

        List<ConstraintDefinition> constraints = new ArrayList<>();
        for (Map<String, Object> constraintSource : YamlMappingUtils.asMapList(root.get("constraints"))) {
            constraints.add(YamlMappingUtils.toConstraintDefinition(constraintSource));
        }
        constraintsDefinition.setConstraints(constraints);
        return constraintsDefinition;
    }

    public ReferenceDefinition loadReference(String name) {
        String path = "references/" + name + ".yaml";
        Map<String, Object> root = loadYamlMap(path);
        ReferenceDefinition reference = new ReferenceDefinition();
        reference.setName(YamlMappingUtils.asString(root.get("name")));
        if (reference.getName() == null) {
            reference.setName(name);
        }
        reference.setReader(YamlMappingUtils.asMap(root.get("reader")));
        return reference;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYamlMap(String path) {
        try (InputStream inputStream = pathResolver.open(path)) {
            Object loaded = new Yaml().load(inputStream);
            if (loaded == null) {
                throw new ConfigLoadException("Empty YAML config: " + path);
            }
            return YamlMappingUtils.asMap(loaded);
        } catch (ConfigLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            throw new ConfigLoadException("Failed to load YAML config: " + path + " (" + detail + ")", exception);
        }
    }
}

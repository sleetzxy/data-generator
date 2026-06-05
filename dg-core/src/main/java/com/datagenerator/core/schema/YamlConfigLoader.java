package com.datagenerator.core.schema;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YamlConfigLoader {

    private final ConfigPathResolver pathResolver;
    private final Yaml yaml = new Yaml();

    public YamlConfigLoader(ConfigPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public SchemaDefinition loadSchema(String path) {
        Map<String, Object> root = loadYamlMap(path);
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable(YamlMappingUtils.asString(root.get("table")));
        schema.setConstraints(YamlMappingUtils.asString(root.get("constraints")));
        schema.setSeed(YamlMappingUtils.asMap(root.get("seed")));

        List<FieldDefinition> fields = new ArrayList<>();
        for (Map<String, Object> fieldSource : YamlMappingUtils.asMapList(root.get("fields"))) {
            fields.add(YamlMappingUtils.toFieldDefinition(fieldSource));
        }
        schema.setFields(fields);
        return schema;
    }

    public JobDefinition loadJob(String path) {
        Map<String, Object> root = loadYamlMap(path);
        JobDefinition job = new JobDefinition();
        job.setJob(YamlMappingUtils.asString(root.get("job")));
        job.setConstraints(YamlMappingUtils.asString(root.get("constraints")));

        List<TableTask> tables = new ArrayList<>();
        for (Map<String, Object> tableSource : YamlMappingUtils.asMapList(root.get("tables"))) {
            tables.add(YamlMappingUtils.toTableTask(tableSource));
        }
        job.setTables(tables);
        return job;
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
            Object loaded = yaml.load(inputStream);
            if (loaded == null) {
                throw new ConfigLoadException("Empty YAML config: " + path);
            }
            return YamlMappingUtils.asMap(loaded);
        } catch (ConfigLoadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConfigLoadException("Failed to load YAML config: " + path, exception);
        }
    }
}

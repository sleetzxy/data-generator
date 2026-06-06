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
        return toSchemaDefinition(loadYamlMap(path));
    }

    public SchemaDefinition toSchemaDefinition(Map<String, Object> root) {
        return YamlMappingUtils.toSchemaDefinition(root);
    }

    public JobDefinition loadJob(String path) {
        Map<String, Object> root = loadYamlMap(path);
        JobDefinition job = new JobDefinition();
        job.setJob(YamlMappingUtils.asString(root.get("job")));
        String id = YamlMappingUtils.asString(root.get("id"));
        if (id != null && !id.isBlank()) {
            job.setId(id);
        } else if (job.getJob() != null && !job.getJob().isBlank()) {
            job.setId(job.getJob());
        }
        Object constraintsValue = root.get("constraints");
        if (constraintsValue instanceof List<?>) {
            job.setInlineConstraints(YamlMappingUtils.toConstraintDefinitions(constraintsValue));
        } else {
            job.setConstraints(YamlMappingUtils.asString(constraintsValue));
        }
        job.setWriter(YamlMappingUtils.asMap(root.get("writer")));

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

package com.datagenerator.core.schema;

import com.datagenerator.core.engine.SeedDependencySorter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JobSeedValidator {

    private JobSeedValidator() {
    }

    public static void validate(JobDefinition job, YamlConfigLoader configLoader) {
        List<SeedDefinition> seeds = job.getSeeds();
        if (seeds.isEmpty()) {
            validateInlineSchemasWithoutJobSeeds(job);
            return;
        }

        SeedDependencySorter.sort(List.copyOf(seeds));

        Set<String> seedNames = new HashSet<>();
        for (SeedDefinition seed : seeds) {
            validateSeedDefinition(seed);
            seedNames.add(seed.getName());
        }

        for (TableTask table : job.getTables()) {
            SchemaDefinition schema = resolveSchema(table, configLoader);
            validateSchemaSeedFields(schema, seedNames);
        }
    }

    private static void validateInlineSchemasWithoutJobSeeds(JobDefinition job) {
        for (TableTask table : job.getTables()) {
            if (table.getSchemaDefinition() == null) {
                continue;
            }
            for (FieldDefinition field : table.getSchemaDefinition().getFields()) {
                Map<String, Object> generator = field.getGenerator();
                if ("seed".equals(String.valueOf(generator.get("strategy")))) {
                    throw new ConfigLoadException(
                            "Field '" + field.getName() + "' uses strategy seed but job defines no seeds");
                }
            }
        }
    }

    private static void validateSeedDefinition(SeedDefinition seed) {
        if (seed.getName() == null || seed.getName().isBlank()) {
            throw new ConfigLoadException("seed name is required");
        }

        int sourceCount = 0;
        if (!seed.getReader().isEmpty()) {
            sourceCount++;
        }
        if (seed.getReference() != null && !seed.getReference().isBlank()) {
            sourceCount++;
        }
        if (!seed.getTemplate().isEmpty()) {
            sourceCount++;
        }
        if (sourceCount != 1) {
            throw new ConfigLoadException(
                    "Seed '" + seed.getName() + "' requires exactly one of reader, reference, or template");
        }

        if (seed.getLink() != null) {
            if (seed.getLink().getSeed() == null || seed.getLink().getSeed().isBlank()) {
                throw new ConfigLoadException("Seed '" + seed.getName() + "' link.seed is required");
            }
            seed.getLink().resolveParentColumn();
        }
    }

    private static void validateSchemaSeedFields(SchemaDefinition schema, Set<String> seedNames) {
        for (FieldDefinition field : schema.getFields()) {
            Map<String, Object> generator = field.getGenerator();
            if (!"seed".equals(String.valueOf(generator.get("strategy")))) {
                continue;
            }
            Object source = generator.get("source");
            if (source == null || String.valueOf(source).isBlank()) {
                throw new ConfigLoadException(
                        "Field '" + field.getName() + "' with strategy seed requires source");
            }
            if (!seedNames.contains(String.valueOf(source))) {
                throw new ConfigLoadException("Unknown seed source: " + source);
            }
        }
    }

    private static SchemaDefinition resolveSchema(TableTask table, YamlConfigLoader configLoader) {
        if (table.getSchemaDefinition() != null) {
            return table.getSchemaDefinition();
        }
        if (table.getSchema() == null || table.getSchema().isBlank()) {
            throw new ConfigLoadException("Table '" + table.getName() + "' has no schema defined");
        }
        return configLoader.loadSchema(table.getSchema());
    }
}

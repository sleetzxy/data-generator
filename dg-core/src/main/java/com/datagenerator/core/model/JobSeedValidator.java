package com.datagenerator.core.model;

import com.datagenerator.core.engine.SeedDependencySorter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JobSeedValidator {

    private JobSeedValidator() {
    }

    public static void validate(TaskConfig job, YamlConfigLoader configLoader) {
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
            TableSchema schema = resolveSchema(table, configLoader);
            validateSchemaSeedFields(schema, seedNames);
        }
    }

    private static void validateInlineSchemasWithoutJobSeeds(TaskConfig job) {
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
            validateLinkedSeedMatchRules(seed);
        }
    }

    private static void validateLinkedSeedMatchRules(SeedDefinition seed) {
        // template 从属 seed 不执行 SQL 预加载，仅需 parent_field 声明关联列
        if (!seed.getTemplate().isEmpty()) {
            return;
        }

        rejectLinkPlaceholders(seed, resolveReaderQuery(seed));

        if (!seed.getLink().getSources().isEmpty()) {
            for (SeedLinkSourceDefinition source : seed.getLink().getSources()) {
                if (source.getMatch() == null || source.getMatch().isBlank()) {
                    throw new ConfigLoadException("Seed '" + seed.getName() + "' link.sources 缺少 match");
                }
                if (source.getColumn() == null || source.getColumn().isBlank()) {
                    throw new ConfigLoadException("Seed '" + seed.getName() + "' link.sources 缺少 column");
                }
                String loadQuery = source.getQuery() != null && !source.getQuery().isBlank()
                        ? source.getQuery()
                        : source.getTable();
                if (loadQuery == null || loadQuery.isBlank()) {
                    throw new ConfigLoadException("Seed '" + seed.getName() + "' link.sources 需要 query 或 table");
                }
                rejectLinkPlaceholders(seed, loadQuery);
            }
            return;
        }

        if (seed.getLink().getMatch() == null || seed.getLink().getMatch().isBlank()) {
            throw new ConfigLoadException(
                    "Seed '" + seed.getName() + "' 的 link seed 须声明 match 或 sources");
        }

        Object query = seed.getReader().get("query");
        if (query == null || String.valueOf(query).isBlank()) {
            throw new ConfigLoadException("Seed '" + seed.getName() + "' 声明 link.match 时需要 reader.query");
        }
    }

    private static String resolveReaderQuery(SeedDefinition seed) {
        if (seed.getReader().isEmpty()) {
            return null;
        }
        Object query = seed.getReader().get("query");
        return query == null ? null : String.valueOf(query);
    }

    private static void rejectLinkPlaceholders(SeedDefinition seed, String query) {
        if (query == null) {
            return;
        }
        if (query.contains(":link_id") || query.contains(":link.")) {
            throw new ConfigLoadException(
                    "Seed '" + seed.getName() + "' 的 link seed 不支持 SQL 占位符，请在 link 中声明 match/sources");
        }
    }

    private static void validateSchemaSeedFields(TableSchema schema, Set<String> seedNames) {
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

    private static TableSchema resolveSchema(TableTask table, YamlConfigLoader configLoader) {
        if (table.getSchemaDefinition() != null) {
            return table.getSchemaDefinition();
        }
        if (table.getSchema() == null || table.getSchema().isBlank()) {
            throw new ConfigLoadException("Table '" + table.getName() + "' has no schema defined");
        }
        return configLoader.loadSchema(table.getSchema());
    }
}

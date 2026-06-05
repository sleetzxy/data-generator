package com.datagenerator.core.schema;

public final class OverridePathResolver {

    private OverridePathResolver() {
    }

    public static TableTask resolveTable(JobDefinition job, String overridePath) {
        String[] parts = overridePath.split("\\.", 3);
        if (parts.length < 3 || !"tables".equals(parts[0])) {
            throw new ConfigLoadException("Invalid override path (expected tables.{name}.{field}): " + overridePath);
        }
        return job.findTable(parts[1])
                .orElseThrow(() -> new ConfigLoadException("Unknown table in override path: " + parts[1]));
    }

    public static String resolveField(TableTask table, String overridePath) {
        String[] parts = overridePath.split("\\.", 3);
        if (parts.length < 3 || !"tables".equals(parts[0]) || !table.getName().equals(parts[1])) {
            throw new ConfigLoadException("Override path does not match table " + table.getName() + ": " + overridePath);
        }
        return parts[2];
    }
}

package com.datagenerator.core.generator;

import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于内联种子模板生成行：先复制 template 字段，再按 mutate 列表用字段生成器覆盖。
 */
public class SeedTemplateApplier {

    private final GeneratorRegistry generatorRegistry;

    public SeedTemplateApplier(GeneratorRegistry generatorRegistry) {
        this.generatorRegistry = generatorRegistry;
    }

    public DataRow apply(SchemaDefinition schema, GenerationContext context) {
        Map<String, Object> seedConfig = schema.getSeed();
        if (seedConfig.isEmpty()) {
            return null;
        }
        DataRow row = loadInlineTemplate(seedConfig);
        Set<String> mutateFields = resolveMutateFields(seedConfig, schema);

        for (FieldDefinition field : schema.getFields()) {
            if (!mutateFields.contains(field.getName())) {
                continue;
            }
            Map<String, Object> generatorConfig = field.getGenerator();
            if (generatorConfig.isEmpty()) {
                continue;
            }
            String strategy = String.valueOf(generatorConfig.getOrDefault("strategy", ""));
            Object value = generatorRegistry.get(strategy).generate(context, generatorConfig);
            row.set(field.getName(), value);
        }
        return row;
    }

    private static DataRow loadInlineTemplate(Map<String, Object> seedConfig) {
        Object inlineTemplate = seedConfig.get("template");
        if (!(inlineTemplate instanceof Map<?, ?> templateMap)) {
            throw new IllegalArgumentException("seed.template inline map is required");
        }
        DataRow row = new DataRow();
        templateMap.forEach((key, value) -> row.set(String.valueOf(key), value));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> resolveMutateFields(Map<String, Object> seedConfig, SchemaDefinition schema) {
        Object mutate = seedConfig.get("mutate");
        if (mutate instanceof List<?> mutateList) {
            Set<String> fields = new HashSet<>();
            for (Object item : mutateList) {
                if (item instanceof String fieldName) {
                    fields.add(fieldName);
                } else if (item instanceof Map<?, ?> fieldMap && fieldMap.get("field") != null) {
                    fields.add(String.valueOf(fieldMap.get("field")));
                }
            }
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        Set<String> allFields = new HashSet<>();
        schema.getFields().forEach(field -> allFields.add(field.getName()));
        return allFields;
    }
}

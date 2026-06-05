package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.ReferenceDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.GenerationContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于内联种子模板生成行：先复制 template 字段，再按 mutate 列表用字段生成器覆盖。
 */
public class SeedTemplateApplier {

    private final GeneratorRegistry generatorRegistry;
    private final ReferenceDataLoader referenceDataLoader;
    private final YamlConfigLoader configLoader;

    public SeedTemplateApplier(GeneratorRegistry generatorRegistry) {
        this(generatorRegistry, null, null);
    }

    public SeedTemplateApplier(
            GeneratorRegistry generatorRegistry,
            ReferenceDataLoader referenceDataLoader,
            YamlConfigLoader configLoader) {
        this.generatorRegistry = generatorRegistry;
        this.referenceDataLoader = referenceDataLoader;
        this.configLoader = configLoader;
    }

    public DataRow apply(SchemaDefinition schema, GenerationContext context) {
        Map<String, Object> seedConfig = schema.getSeed();
        if (seedConfig.isEmpty()) {
            return null;
        }
        DataRow row = loadTemplate(seedConfig);
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

    private DataRow loadTemplate(Map<String, Object> seedConfig) {
        Object reader = seedConfig.get("reader");
        if (reader instanceof Map<?, ?> readerMap) {
            return loadReaderTemplate(asStringMap(readerMap));
        }
        Object referenceName = seedConfig.get("reference");
        if (referenceName != null && !String.valueOf(referenceName).isBlank()) {
            return loadReferenceTemplate(String.valueOf(referenceName));
        }
        return loadInlineTemplate(seedConfig);
    }

    private DataRow loadReaderTemplate(Map<String, Object> readerMap) {
        if (referenceDataLoader == null) {
            throw new IllegalStateException("seed.reader requires ReferenceDataLoader");
        }
        String readerType = String.valueOf(readerMap.getOrDefault("type", "postgresql"));
        Map<String, Object> loadConfig = new HashMap<>();
        loadConfig.put("reader", readerMap);
        DataRow sampled = referenceDataLoader.sampleRow(readerType, loadConfig);
        return new DataRow(sampled.getFields());
    }

    private DataRow loadReferenceTemplate(String referenceName) {
        if (referenceDataLoader == null || configLoader == null) {
            throw new IllegalStateException("Reference seed requires ReferenceDataLoader and YamlConfigLoader");
        }
        ReferenceDefinition reference = configLoader.loadReference(referenceName);
        Map<String, Object> reader = reference.getReader();
        if (reader.isEmpty()) {
            throw new IllegalArgumentException("Reference '" + referenceName + "' is missing reader config");
        }
        String readerType = String.valueOf(reader.getOrDefault("type", "postgresql"));
        Map<String, Object> loadConfig = new HashMap<>();
        loadConfig.put("reader", reader);
        DataRow sampled = referenceDataLoader.sampleRow(readerType, loadConfig);
        return new DataRow(sampled.getFields());
    }

    private static DataRow loadInlineTemplate(Map<String, Object> seedConfig) {
        Object inlineTemplate = seedConfig.get("template");
        if (!(inlineTemplate instanceof Map<?, ?> templateMap)) {
            throw new IllegalArgumentException("seed.template inline map is required when reference is absent");
        }
        DataRow row = new DataRow();
        templateMap.forEach((key, value) -> row.set(String.valueOf(key), value));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
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

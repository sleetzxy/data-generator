package com.datagenerator.core.engine;



import com.datagenerator.core.reference.ReferenceDataLoader;

import com.datagenerator.core.model.FieldDefinition;

import com.datagenerator.core.model.ReferenceDefinition;

import com.datagenerator.core.model.TableSchema;

import com.datagenerator.core.model.SeedDefinition;

import com.datagenerator.core.model.SeedLinkDefinition;

import com.datagenerator.core.model.YamlConfigLoader;

import com.datagenerator.spi.model.DataRow;



import java.util.HashMap;

import java.util.HashSet;

import java.util.List;

import java.util.Map;

import java.util.Set;



/**

 * 按拓扑序采样 job 级 seeds；link seed 在启动阶段一次性加载并在内存中按关联列匹配。

 */

public class SeedSampler {



    private final ReferenceDataLoader referenceDataLoader;

    private final YamlConfigLoader configLoader;

    private final List<SeedDefinition> sortedSeeds;

    private final Map<String, SeedDefinition> seedsByName;

    private final LinkedSeedIndex linkedSeedIndex;

    private boolean linkedSeedsPreloaded;



    public SeedSampler(

            ReferenceDataLoader referenceDataLoader,

            YamlConfigLoader configLoader,

            List<SeedDefinition> sortedSeeds) {

        this.referenceDataLoader = referenceDataLoader;

        this.configLoader = configLoader;

        this.sortedSeeds = sortedSeeds;

        this.seedsByName = new HashMap<>();

        for (SeedDefinition seed : sortedSeeds) {

            this.seedsByName.put(seed.getName(), seed);

        }

        this.linkedSeedIndex = new LinkedSeedIndex(referenceDataLoader, configLoader);

    }



    /**

     * 为当前行采样所需 seeds；单个 seed 无数据时使用空行占位，缺失字段由 {@link com.datagenerator.core.generator.SeedGenerator} 输出 null。

     */

    public Map<String, DataRow> sample(TableSchema schema) {

        return sample(schema, -1, null);

    }



    /**

     * 为当前行采样 seeds；若 {@link SeedRowSnapshotStore} 中已有同行快照则优先复用（L2 行级缓存）。

     */

    public Map<String, DataRow> sample(TableSchema schema, int rowIndex, SeedRowSnapshotStore snapshotStore) {

        Set<String> fieldSources = collectFieldSources(schema);

        if (fieldSources.isEmpty()) {

            return Map.of();

        }



        Set<String> requiredNames = SeedDependencySorter.collectRequiredSeedNames(sortedSeeds, fieldSources);

        if (snapshotStore != null && rowIndex >= 0 && snapshotStore.containsAll(rowIndex, requiredNames)) {

            return snapshotStore.pick(rowIndex, requiredNames);

        }



        Map<String, DataRow> samples = new HashMap<>();

        for (SeedDefinition seed : sortedSeeds) {

            if (!requiredNames.contains(seed.getName())) {

                continue;

            }

            if (snapshotStore != null && rowIndex >= 0) {

                Map<String, DataRow> rowSnapshot = snapshotStore.getRow(rowIndex);

                if (rowSnapshot != null && rowSnapshot.containsKey(seed.getName())) {

                    samples.put(seed.getName(), rowSnapshot.get(seed.getName()));

                    continue;

                }

            }

            DataRow sampled = sampleSeed(seed, samples);

            samples.put(seed.getName(), sampled == null ? new DataRow() : sampled);

        }



        if (snapshotStore != null && rowIndex >= 0) {

            snapshotStore.mergeRow(rowIndex, samples);

        }

        return samples;

    }



    /**

     * 预加载 schema 所需 root seed 与 link seed 数据源，生成阶段不再逐行打库。

     */

    public void preloadCaches(TableSchema schema) {

        preloadLinkedSeeds();



        Set<String> fieldSources = collectFieldSources(schema);

        if (fieldSources.isEmpty()) {

            return;

        }

        Set<String> requiredNames = SeedDependencySorter.collectRequiredSeedNames(sortedSeeds, fieldSources);

        for (SeedDefinition seed : sortedSeeds) {

            if (!requiredNames.contains(seed.getName()) || !seed.isRoot()) {

                continue;

            }

            if (!seed.getTemplate().isEmpty()) {

                continue;

            }

            if (seed.getReader().isEmpty()

                    && (seed.getReference() == null || seed.getReference().isBlank())) {

                continue;

            }

            Map<String, Object> loadConfig = buildLoadConfig(seed);

            String readerType = resolveReaderType(seed, loadConfig);

            referenceDataLoader.loadRows(readerType, loadConfig);

        }

    }



    private void preloadLinkedSeeds() {

        if (linkedSeedsPreloaded) {

            return;

        }

        List<SeedDefinition> linkedSeeds = sortedSeeds.stream()

                .filter(seed -> !seed.isRoot())

                .toList();

        linkedSeedIndex.preload(linkedSeeds);

        linkedSeedsPreloaded = true;

    }



    private DataRow sampleSeed(SeedDefinition seed, Map<String, DataRow> existingSamples) {

        try {

            if (seed.isRoot()) {

                return loadRootSeed(seed);

            }

            SeedLinkDefinition link = seed.getLink();

            DataRow parentRow = existingSamples.get(link.getSeed());

            if (parentRow == null) {

                return null;

            }

            return loadLinkedSeed(seed, parentRow, link);

        } catch (RuntimeException exception) {

            return null;

        }

    }



    private DataRow loadRootSeed(SeedDefinition seed) {

        if (!seed.getTemplate().isEmpty()) {

            DataRow row = new DataRow();

            seed.getTemplate().forEach((key, value) -> row.set(String.valueOf(key), value));

            return row;

        }

        Map<String, Object> loadConfig = buildLoadConfig(seed);

        String readerType = resolveReaderType(seed, loadConfig);

        return referenceDataLoader.sampleRow(readerType, loadConfig);

    }



    private DataRow loadLinkedSeed(SeedDefinition seed, DataRow parentRow, SeedLinkDefinition link) {

        if (!seed.getTemplate().isEmpty()) {

            DataRow row = new DataRow();

            seed.getTemplate().forEach((key, value) -> row.set(String.valueOf(key), value));

            return row;

        }



        String parentColumn = link.resolveParentColumn();

        Object linkId = parentRow.get(parentColumn);

        if (linkId == null || !parentRow.getFields().containsKey(parentColumn)) {

            return null;

        }



        preloadLinkedSeeds();

        return linkedSeedIndex.lookup(seed, linkId);

    }



    private Map<String, Object> buildLoadConfig(SeedDefinition seed) {

        if (!seed.getReader().isEmpty()) {

            Map<String, Object> config = new HashMap<>();

            config.put("reader", new HashMap<>(seed.getReader()));

            return config;

        }

        if (seed.getReference() != null && !seed.getReference().isBlank()) {

            ReferenceDefinition reference = configLoader.loadReference(seed.getReference());

            Map<String, Object> config = new HashMap<>();

            config.put("reader", new HashMap<>(reference.getReader()));

            return config;

        }

        throw new IllegalArgumentException("Seed '" + seed.getName() + "' requires reader, reference, or template");

    }



    private static String resolveReaderType(SeedDefinition seed, Map<String, Object> loadConfig) {

        Object reader = loadConfig.get("reader");

        if (reader instanceof Map<?, ?> readerMap) {

            Object type = readerMap.get("type");

            return type == null ? "postgresql" : String.valueOf(type);

        }

        return "postgresql";

    }



    private static Set<String> collectFieldSources(TableSchema schema) {

        Set<String> sources = new HashSet<>();

        for (FieldDefinition field : schema.getFields()) {

            Map<String, Object> generator = field.getGenerator();

            if (generator.isEmpty()) {

                continue;

            }

            if (!"seed".equals(String.valueOf(generator.get("strategy")))) {

                continue;

            }

            Object source = generator.get("source");

            if (source == null || String.valueOf(source).isBlank()) {

                throw new IllegalArgumentException(

                        "Field '" + field.getName() + "' with strategy seed requires source");

            }

            sources.add(String.valueOf(source));

        }

        return sources;

    }

}


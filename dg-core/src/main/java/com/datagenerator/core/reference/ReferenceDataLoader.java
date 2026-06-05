package com.datagenerator.core.reference;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.reference.distribution.DistributionSamplerFactory;
import com.datagenerator.spi.model.DataRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加载参考数据并为生成器提供分布采样。
 */
public class ReferenceDataLoader {

    private final PluginRegistry registry;
    private final ConnectionRegistry connectionRegistry;
    private final Map<String, LookupReferenceSource> sources = new HashMap<>();
    private final Map<String, List<Object>> cache = new HashMap<>();
    private final DistributionSamplerFactory distributionSamplerFactory = new DistributionSamplerFactory();

    public ReferenceDataLoader(PluginRegistry registry) {
        this(registry, null);
    }

    public ReferenceDataLoader(PluginRegistry registry, ConnectionRegistry connectionRegistry) {
        this.registry = registry;
        this.connectionRegistry = connectionRegistry;
    }

    public ReferenceDataLoader(Map<String, com.datagenerator.spi.reader.DataReader> readers) {
        this(readers, null);
    }

    public ReferenceDataLoader(
            Map<String, com.datagenerator.spi.reader.DataReader> readers,
            ConnectionRegistry connectionRegistry) {
        PluginRegistry pluginRegistry = new PluginRegistry();
        readers.forEach(pluginRegistry::registerReader);
        this.registry = pluginRegistry;
        this.connectionRegistry = connectionRegistry;
    }

    public List<Object> load(String source, Map<String, Object> config) {
        String cacheKey = cacheKey(source, config);
        return cache.computeIfAbsent(cacheKey, ignored -> lookupSource(source).load(config));
    }

    public List<DataRow> loadRows(String source, Map<String, Object> config) {
        return lookupSource(source).loadRows(config);
    }

    public Object sample(String source, Map<String, Object> config) {
        List<Object> values = load(source, config);
        if (values.isEmpty()) {
            throw new IllegalStateException("No reference values loaded for source: " + source);
        }
        return distributionSamplerFactory.sample(values, config);
    }

    public DataRow sampleRow(String source, Map<String, Object> config) {
        List<DataRow> rows = loadRows(source, config);
        return rows.get(ThreadLocalRandom.current().nextInt(rows.size()));
    }

    private LookupReferenceSource lookupSource(String source) {
        return sources.computeIfAbsent(
                source, s -> new LookupReferenceSource(registry.getReader(s), connectionRegistry));
    }

    private static String cacheKey(String source, Map<String, Object> config) {
        return source + ":" + config.get("field");
    }
}

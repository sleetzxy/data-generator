package com.datagenerator.core.reference;

import com.datagenerator.core.engine.PluginRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads reference data for {@link com.datagenerator.core.generator.ReferenceGenerator}.
 * Reads via configured {@link com.datagenerator.spi.reader.DataReader} instances and caches by source key.
 */
public class ReferenceDataLoader {

    private final PluginRegistry registry;
    private final Map<String, LookupReferenceSource> sources = new HashMap<>();
    private final Map<String, List<Object>> cache = new HashMap<>();

    public ReferenceDataLoader(PluginRegistry registry) {
        this.registry = registry;
    }

    public ReferenceDataLoader(Map<String, com.datagenerator.spi.reader.DataReader> readers) {
        PluginRegistry pluginRegistry = new PluginRegistry();
        readers.forEach(pluginRegistry::registerReader);
        this.registry = pluginRegistry;
    }

    public List<Object> load(String source, Map<String, Object> config) {
        rejectUnsupportedDistribution(config);
        String cacheKey = cacheKey(source, config);
        return cache.computeIfAbsent(cacheKey, ignored -> lookupSource(source).load(config));
    }

    private LookupReferenceSource lookupSource(String source) {
        return sources.computeIfAbsent(source, s -> new LookupReferenceSource(registry.getReader(s)));
    }

    private static void rejectUnsupportedDistribution(Map<String, Object> config) {
        String distribution = String.valueOf(config.getOrDefault("distribution", "uniform"));
        if ("histogram".equalsIgnoreCase(distribution)) {
            throw new UnsupportedOperationException("Unsupported distribution: histogram");
        }
    }

    private static String cacheKey(String source, Map<String, Object> config) {
        return source + ":" + config.get("field");
    }
}

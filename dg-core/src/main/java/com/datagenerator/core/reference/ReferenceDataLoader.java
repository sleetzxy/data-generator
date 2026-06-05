package com.datagenerator.core.reference;

import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.reference.distribution.DistributionSamplerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载参考数据并为生成器提供分布采样。
 */
public class ReferenceDataLoader {

    private final PluginRegistry registry;
    private final Map<String, LookupReferenceSource> sources = new HashMap<>();
    private final Map<String, List<Object>> cache = new HashMap<>();
    private final DistributionSamplerFactory distributionSamplerFactory = new DistributionSamplerFactory();

    public ReferenceDataLoader(PluginRegistry registry) {
        this.registry = registry;
    }

    public ReferenceDataLoader(Map<String, com.datagenerator.spi.reader.DataReader> readers) {
        PluginRegistry pluginRegistry = new PluginRegistry();
        readers.forEach(pluginRegistry::registerReader);
        this.registry = pluginRegistry;
    }

    public List<Object> load(String source, Map<String, Object> config) {
        String cacheKey = cacheKey(source, config);
        return cache.computeIfAbsent(cacheKey, ignored -> lookupSource(source).load(config));
    }

    public Object sample(String source, Map<String, Object> config) {
        List<Object> values = load(source, config);
        if (values.isEmpty()) {
            throw new IllegalStateException("No reference values loaded for source: " + source);
        }
        return distributionSamplerFactory.sample(values, config);
    }

    private LookupReferenceSource lookupSource(String source) {
        return sources.computeIfAbsent(source, s -> new LookupReferenceSource(registry.getReader(s)));
    }

    private static String cacheKey(String source, Map<String, Object> config) {
        return source + ":" + config.get("field");
    }
}

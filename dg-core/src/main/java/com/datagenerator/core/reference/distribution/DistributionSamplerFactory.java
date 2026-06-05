package com.datagenerator.core.reference.distribution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DistributionSamplerFactory {

    private final Map<String, DistributionSampler> samplers = new HashMap<>();

    public DistributionSamplerFactory() {
        register(new UniformDistributionSampler());
        register(new HistogramDistributionSampler());
        register(new NormalDistributionSampler());
    }

    public void register(DistributionSampler sampler) {
        samplers.put(sampler.distribution().toLowerCase(), sampler);
    }

    public Object sample(List<Object> values, Map<String, Object> config) {
        String distribution = resolveDistributionName(config);
        DistributionSampler sampler = samplers.get(distribution.toLowerCase());
        if (sampler == null) {
            throw new UnsupportedOperationException("Unsupported distribution: " + distribution);
        }
        return sampler.sample(values, config);
    }

    private static String resolveDistributionName(Map<String, Object> config) {
        Object distribution = config.get("distribution");
        if (distribution == null) {
            return "uniform";
        }
        if (distribution instanceof String distributionName) {
            return distributionName;
        }
        if (distribution instanceof Map<?, ?> distributionMap) {
            Object type = distributionMap.get("type");
            if (type != null) {
                return String.valueOf(type);
            }
        }
        return String.valueOf(distribution);
    }
}

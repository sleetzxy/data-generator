package com.datagenerator.core.reference.distribution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class UniformDistributionSampler implements DistributionSampler {

    @Override
    public String distribution() {
        return "uniform";
    }

    @Override
    public Object sample(List<Object> values, Map<String, Object> config) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}

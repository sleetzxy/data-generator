package com.datagenerator.core.reference.distribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 直方图分布：按数值分桶，按桶频率加权随机采样。
 */
public class HistogramDistributionSampler implements DistributionSampler {

    private static final int DEFAULT_BUCKETS = 10;

    @Override
    public String distribution() {
        return "histogram";
    }

    @Override
    public Object sample(List<Object> values, Map<String, Object> config) {
        List<Double> numbers = values.stream()
                .map(HistogramDistributionSampler::toDouble)
                .filter(value -> value != null)
                .toList();
        if (numbers.isEmpty()) {
            throw new IllegalStateException("histogram distribution requires numeric reference values");
        }
        int buckets = resolveBuckets(config);
        double min = numbers.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = numbers.stream().mapToDouble(Double::doubleValue).max().orElse(min);
        if (min == max) {
            return numbers.get(0);
        }

        int[] counts = new int[buckets];
        double width = (max - min) / buckets;
        for (double number : numbers) {
            int index = (int) Math.min(buckets - 1, Math.floor((number - min) / width));
            counts[index]++;
        }

        int total = numbers.size();
        int pick = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (int bucket = 0; bucket < buckets; bucket++) {
            cumulative += counts[bucket];
            if (pick < cumulative) {
                double bucketMin = min + bucket * width;
                double bucketMax = bucket == buckets - 1 ? max : bucketMin + width;
                return bucketMin + ThreadLocalRandom.current().nextDouble() * (bucketMax - bucketMin);
            }
        }
        return numbers.get(numbers.size() - 1);
    }

    private static int resolveBuckets(Map<String, Object> config) {
        Object distribution = config.get("distribution");
        if (distribution instanceof Map<?, ?> distributionMap) {
            Object buckets = distributionMap.get("buckets");
            if (buckets instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        }
        return DEFAULT_BUCKETS;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

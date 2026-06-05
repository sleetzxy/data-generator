package com.datagenerator.core.reference.distribution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 正态分布：基于样本均值/标准差生成，并裁剪到样本 min/max 范围。
 */
public class NormalDistributionSampler implements DistributionSampler {

    @Override
    public String distribution() {
        return "normal";
    }

    @Override
    public Object sample(List<Object> values, Map<String, Object> config) {
        List<Double> numbers = values.stream()
                .map(NormalDistributionSampler::toDouble)
                .filter(value -> value != null)
                .toList();
        if (numbers.isEmpty()) {
            throw new IllegalStateException("normal distribution requires numeric reference values");
        }
        double min = numbers.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = numbers.stream().mapToDouble(Double::doubleValue).max().orElse(min);
        double mean = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(min);
        double variance = numbers.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);
        if (stdDev == 0) {
            return mean;
        }

        double generated = mean + ThreadLocalRandom.current().nextGaussian() * stdDev;
        if (generated < min) {
            return min;
        }
        if (generated > max) {
            return max;
        }
        return generated;
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

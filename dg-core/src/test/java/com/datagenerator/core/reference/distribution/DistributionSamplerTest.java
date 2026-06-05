package com.datagenerator.core.reference.distribution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DistributionSamplerTest {

    @Test
    void histogramDistribution_samplesWithinRange() {
        DistributionSamplerFactory factory = new DistributionSamplerFactory();
        List<Object> values = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0);
        Object sample = factory.sample(values, Map.of("distribution", "histogram"));
        assertThat(((Number) sample).doubleValue()).isBetween(10.0, 100.0);
    }

    @Test
    void normalDistribution_samplesWithinRange() {
        DistributionSamplerFactory factory = new DistributionSamplerFactory();
        List<Object> values = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        Object sample = factory.sample(values, Map.of("distribution", "normal"));
        assertThat(((Number) sample).doubleValue()).isBetween(1.0, 5.0);
    }
}

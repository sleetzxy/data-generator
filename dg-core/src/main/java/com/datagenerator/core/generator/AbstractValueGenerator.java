package com.datagenerator.core.generator;

import com.datagenerator.spi.generator.ValueGenerator;

abstract class AbstractValueGenerator implements ValueGenerator {

    private final String strategy;

    protected AbstractValueGenerator(String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String strategy() {
        return strategy;
    }

    @Override
    public String type() {
        return strategy;
    }
}

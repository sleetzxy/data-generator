package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.generator.ValueGenerator;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 生成策略注册表。默认空表，由调用方注册；内置策略见 {@link #withBuiltins(ReferenceDataLoader)}。
 */
public class GeneratorRegistry {

    private final Map<String, ValueGenerator> generators = new HashMap<>();

    public GeneratorRegistry() {
    }

    /** 预装全部内置策略，供测试与无 Spring 场景使用。 */
    public static GeneratorRegistry withBuiltins(ReferenceDataLoader referenceDataLoader) {
        GeneratorRegistry registry = new GeneratorRegistry();
        BuiltinGenerators.create(referenceDataLoader).forEach(registry::register);
        return registry;
    }

    public void register(ValueGenerator generator) {
        Objects.requireNonNull(generator, "generator");
        generators.put(generator.strategy(), generator);
    }

    public ValueGenerator get(String strategy) {
        ValueGenerator generator = generators.get(strategy);
        if (generator == null) {
            throw new IllegalArgumentException("Unknown generator strategy: " + strategy);
        }
        return generator;
    }
}

package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.generator.ValueGenerator;

import java.util.HashMap;
import java.util.Map;

public class GeneratorRegistry {

    private final Map<String, ValueGenerator> generators = new HashMap<>();

    public GeneratorRegistry() {
        this(null);
    }

    public GeneratorRegistry(ReferenceDataLoader referenceDataLoader) {
        register(new RandomGenerator());
        register(new SequenceGenerator());
        register(new EnumGenerator());
        register(new RegexGenerator());
        register(new SeedGenerator());
        register(new ReferenceGenerator(referenceDataLoader));
        register(new ExpressionGenerator());
        register(new UuidGenerator());
        register(new PhoneGenerator());
        register(new EmailGenerator());
        register(new LiteralGenerator());
        register(new IdCardGenerator());
    }

    public void register(ValueGenerator generator) {
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

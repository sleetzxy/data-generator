package com.datagenerator.core.engine;

import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.generator.ValueGenerator;
import com.datagenerator.spi.reader.DataReader;
import com.datagenerator.spi.writer.DataWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class PluginRegistry {

    private final Map<String, DataReader> readers = new HashMap<>();
    private final Map<String, DataWriter> writers = new HashMap<>();
    private final GeneratorRegistry generatorRegistry;
    private final ConstraintValidatorRegistry constraintRegistry;
    private final ReferenceDataLoader referenceDataLoader;

    public PluginRegistry() {
        this.constraintRegistry = new ConstraintValidatorRegistry();
        this.referenceDataLoader = new ReferenceDataLoader(this);
        this.generatorRegistry = new GeneratorRegistry(referenceDataLoader);
    }

    public PluginRegistry(ReferenceDataLoader referenceDataLoader) {
        this.constraintRegistry = new ConstraintValidatorRegistry();
        this.referenceDataLoader = referenceDataLoader;
        this.generatorRegistry = new GeneratorRegistry(referenceDataLoader);
    }

    public void registerReader(String source, DataReader reader) {
        readers.put(Objects.requireNonNull(source, "source"), Objects.requireNonNull(reader, "reader"));
    }

    public void registerWriter(String type, DataWriter writer) {
        writers.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(writer, "writer"));
    }

    public void registerGenerator(ValueGenerator generator) {
        generatorRegistry.register(generator);
    }

    public void registerConstraintValidator(ConstraintValidator validator) {
        constraintRegistry.register(validator);
    }

    public DataReader getReader(String source) {
        DataReader reader = readers.get(source);
        if (reader == null) {
            throw new IllegalArgumentException("Unknown reference source: " + source);
        }
        return reader;
    }

    public DataWriter getWriter(String type) {
        DataWriter writer = writers.get(type);
        if (writer == null) {
            throw new IllegalArgumentException("Unknown writer type: " + type);
        }
        return writer;
    }

    public ValueGenerator getGenerator(String strategy) {
        return generatorRegistry.get(strategy);
    }

    public GeneratorRegistry getGeneratorRegistry() {
        return generatorRegistry;
    }

    public ReferenceDataLoader getReferenceDataLoader() {
        return referenceDataLoader;
    }

    public ConstraintValidatorRegistry getConstraintRegistry() {
        return constraintRegistry;
    }
}

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
import java.util.function.Supplier;

public class PluginRegistry {

    private final Map<String, DataReader> readers = new HashMap<>();
    private final Map<String, Supplier<DataWriter>> writerFactories = new HashMap<>();
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

    /**
     * 注册共享 Writer 实例（仅用于单线程测试）。
     */
    public void registerWriter(String type, DataWriter writer) {
        Objects.requireNonNull(writer, "writer");
        registerWriterFactory(type, () -> writer);
    }

    /**
     * 注册 Writer 工厂，每次 {@link #getWriter(String)} 创建新实例，供并发任务隔离使用。
     */
    public void registerWriterFactory(String type, Supplier<DataWriter> factory) {
        writerFactories.put(
                Objects.requireNonNull(type, "type"), Objects.requireNonNull(factory, "factory"));
    }

    /**
     * 根据 Spring 容器中的 Writer 原型注册工厂（通过无参构造反射创建新实例）。
     */
    public void registerWriterPrototype(String type, DataWriter prototype) {
        Objects.requireNonNull(prototype, "prototype");
        Class<? extends DataWriter> clazz = prototype.getClass();
        registerWriterFactory(type, () -> newWriterInstance(clazz));
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
        Supplier<DataWriter> factory = writerFactories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown writer type: " + type);
        }
        return factory.get();
    }

    private static DataWriter newWriterInstance(Class<? extends DataWriter> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create writer: " + clazz.getName(), e);
        }
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

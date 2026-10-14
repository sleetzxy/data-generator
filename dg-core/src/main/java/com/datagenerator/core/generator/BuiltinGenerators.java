package com.datagenerator.core.generator;

import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.spi.generator.ValueGenerator;

import java.util.List;

/**
 * 内置生成策略工厂。Spring 装配与无容器场景均通过此入口注册，外部插件可再追加 {@link ValueGenerator}。
 */
public final class BuiltinGenerators {

    private BuiltinGenerators() {
    }

    /** 创建全部内置策略实例；{@code referenceDataLoader} 可为 null（reference 策略运行时再校验）。 */
    public static List<ValueGenerator> create(ReferenceDataLoader referenceDataLoader) {
        return List.of(
                new RandomGenerator(),
                new SequenceGenerator(),
                new EnumGenerator(),
                new RegexGenerator(),
                new SeedGenerator(),
                new ReferenceGenerator(referenceDataLoader),
                new ExpressionGenerator(),
                new UuidGenerator(),
                new PhoneGenerator(),
                new EmailGenerator(),
                new LiteralGenerator(),
                new IdCardGenerator());
    }
}

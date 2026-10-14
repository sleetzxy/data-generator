package com.datagenerator.web.config;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.constraint.ConstraintPipeline;
import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.engine.TableGenerator;
import com.datagenerator.core.generator.BuiltinGenerators;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.spi.generator.ValueGenerator;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.reader.DataReader;
import com.datagenerator.spi.writer.DataWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DataGeneratorProperties.class)
public class DataGeneratorAutoConfiguration {

    @Bean
    ConfigPathResolver configPathResolver(DataGeneratorProperties properties) {
        // 配置统一由 writable-config-dir 提供：主目录与可写层指向同一目录
        Path writableOverlay = Path.of(properties.getWritableConfigDir()).toAbsolutePath().normalize();
        return ConfigPathResolver.forConfigDir(writableOverlay).withWritableOverlay(writableOverlay);
    }

    @Bean
    YamlConfigLoader yamlConfigLoader(ConfigPathResolver configPathResolver) {
        return new YamlConfigLoader(configPathResolver);
    }

    @Bean
    TaskRunRuntimeSettings taskRunRuntimeSettings(DataGeneratorProperties properties) {
        DataGeneratorProperties.TaskRunProperties taskRunProps = properties.getTaskRun();
        return new TaskRunRuntimeSettings(
                taskRunProps.getSyncThreshold(),
                taskRunProps.getBatchSize(),
                taskRunProps.getThreadPoolSize(),
                taskRunProps.getGenerationParallelism());
    }

    @Bean
    ConnectionRegistry connectionRegistry() {
        // 连接仅来自任务 YAML 的 connections / 内联，不再从 application.yml 注册全局连接
        return new ConnectionRegistry();
    }

    @Bean
    ReferenceDataLoader referenceDataLoader(List<DataReader> readers, ConnectionRegistry connectionRegistry) {
        Map<String, DataReader> readerMap = new HashMap<>();
        for (DataReader reader : readers) {
            readerMap.put(reader.type(), reader);
        }
        return new ReferenceDataLoader(readerMap, connectionRegistry);
    }

    @Bean
    GeneratorRegistry generatorRegistry(
            ReferenceDataLoader referenceDataLoader,
            ObjectProvider<ValueGenerator> extraGenerators) {
        // 内置策略先注册；插件/自定义 Bean 后注册，可覆盖同名 strategy
        GeneratorRegistry registry = new GeneratorRegistry();
        BuiltinGenerators.create(referenceDataLoader).forEach(registry::register);
        extraGenerators.orderedStream().forEach(registry::register);
        return registry;
    }

    @Bean
    PluginRegistry pluginRegistry(
            ReferenceDataLoader referenceDataLoader,
            GeneratorRegistry generatorRegistry,
            List<DataReader> readers,
            List<DataWriter> writers,
            ObjectProvider<ConnectionInspector> inspectors) {
        PluginRegistry registry = new PluginRegistry(referenceDataLoader, generatorRegistry);
        for (DataReader reader : readers) {
            registry.registerReader(reader.type(), reader);
        }
        for (DataWriter writer : writers) {
            registry.registerWriterPrototype(writer.type(), writer);
        }
        // 无 inspector Bean 时为空流，便于不含插件的测试装配
        inspectors.orderedStream()
                .forEach(inspector -> registry.registerInspector(inspector.type(), inspector));
        return registry;
    }

    @Bean
    ConstraintValidatorRegistry constraintValidatorRegistry(PluginRegistry pluginRegistry) {
        return pluginRegistry.getConstraintRegistry();
    }

    @Bean
    ConstraintPipeline constraintPipeline(ConstraintValidatorRegistry constraintValidatorRegistry) {
        return new ConstraintPipeline(List.of(), constraintValidatorRegistry);
    }

    @Bean
    ConstraintLoader constraintLoader(YamlConfigLoader yamlConfigLoader) {
        return new ConstraintLoader(yamlConfigLoader);
    }

    @Bean
    TableGenerator tableGenerator(PluginRegistry pluginRegistry, YamlConfigLoader yamlConfigLoader) {
        return new TableGenerator(pluginRegistry, yamlConfigLoader);
    }

    @Bean
    TaskRunOrchestrator taskRunOrchestrator(
            YamlConfigLoader yamlConfigLoader,
            ConstraintLoader constraintLoader,
            TableGenerator tableGenerator,
            PluginRegistry pluginRegistry,
            ConnectionRegistry connectionRegistry) {
        return new TaskRunOrchestrator(
                yamlConfigLoader,
                constraintLoader,
                tableGenerator,
                pluginRegistry,
                connectionRegistry);
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduleScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("task-schedule-");
        scheduler.initialize();
        return scheduler;
    }
}

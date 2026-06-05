package com.datagenerator.web.config;

import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.constraint.ConstraintPipeline;
import com.datagenerator.core.constraint.ConstraintValidatorRegistry;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.engine.TableGenerator;
import com.datagenerator.core.generator.GeneratorRegistry;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.reader.DataReader;
import com.datagenerator.spi.writer.DataWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(DataGeneratorProperties.class)
public class DataGeneratorAutoConfiguration {

    @Bean
    ConfigPathResolver configPathResolver(DataGeneratorProperties properties) {
        Path writableOverlay = Path.of(properties.getWritableConfigDir()).toAbsolutePath().normalize();
        return ConfigPathResolver.fromSetting(
                properties.getConfigDir(),
                DataGeneratorAutoConfiguration.class.getClassLoader(),
                writableOverlay);
    }

    @Bean
    YamlConfigLoader yamlConfigLoader(ConfigPathResolver configPathResolver) {
        return new YamlConfigLoader(configPathResolver);
    }

    @Bean
    JobRuntimeSettings jobRuntimeSettings(DataGeneratorProperties properties) {
        DataGeneratorProperties.JobProperties job = properties.getJob();
        return new JobRuntimeSettings(job.getSyncThreshold(), job.getBatchSize(), job.getThreadPoolSize());
    }

    @Bean
    ConnectionRegistry connectionRegistry(DataGeneratorProperties properties) {
        return new ConnectionRegistry(properties.getConnections());
    }

    @Bean
    ReferenceDataLoader referenceDataLoader(List<DataReader> readers) {
        Map<String, DataReader> readerMap = new HashMap<>();
        for (DataReader reader : readers) {
            readerMap.put(reader.type(), reader);
        }
        return new ReferenceDataLoader(readerMap);
    }

    @Bean
    PluginRegistry pluginRegistry(
            ReferenceDataLoader referenceDataLoader,
            List<DataReader> readers,
            List<DataWriter> writers) {
        PluginRegistry registry = new PluginRegistry(referenceDataLoader);
        for (DataReader reader : readers) {
            registry.registerReader(reader.type(), reader);
        }
        for (DataWriter writer : writers) {
            registry.registerWriter(writer.type(), writer);
        }
        return registry;
    }

    @Bean
    GeneratorRegistry generatorRegistry(PluginRegistry pluginRegistry) {
        return pluginRegistry.getGeneratorRegistry();
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
    TableGenerator tableGenerator(PluginRegistry pluginRegistry) {
        return new TableGenerator(pluginRegistry);
    }

    @Bean
    JobOrchestrator jobOrchestrator(
            YamlConfigLoader yamlConfigLoader,
            ConstraintLoader constraintLoader,
            TableGenerator tableGenerator,
            PluginRegistry pluginRegistry,
            ConnectionRegistry connectionRegistry) {
        return new JobOrchestrator(
                yamlConfigLoader,
                constraintLoader,
                tableGenerator,
                pluginRegistry,
                connectionRegistry);
    }
}

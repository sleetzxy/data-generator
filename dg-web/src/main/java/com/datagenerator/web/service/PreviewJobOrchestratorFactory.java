package com.datagenerator.web.service;

import com.datagenerator.web.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.engine.TableGenerator;
import com.datagenerator.core.reference.ReferenceDataLoader;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.spi.reader.DataReader;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PreviewJobOrchestratorFactory {

    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;
    private final ReferenceDataLoader referenceDataLoader;
    private final List<DataReader> readers;

    public PreviewJobOrchestratorFactory(
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            ReferenceDataLoader referenceDataLoader,
            List<DataReader> readers) {
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
        this.referenceDataLoader = referenceDataLoader;
        this.readers = readers;
    }

    public JobOrchestrator create(CollectingWriter collectingWriter) {
        PluginRegistry previewRegistry = new PluginRegistry(referenceDataLoader);
        for (DataReader reader : readers) {
            previewRegistry.registerReader(reader.type(), reader);
        }
        previewRegistry.registerWriter(CollectingWriter.TYPE, collectingWriter);
        return new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(previewRegistry, configLoader),
                previewRegistry,
                connectionRegistry);
    }
}

package com.datagenerator.api.service;

import com.datagenerator.api.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.engine.TableGenerator;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.springframework.stereotype.Component;

@Component
public class PreviewJobOrchestratorFactory {

    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;

    public PreviewJobOrchestratorFactory(
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry) {
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
    }

    public JobOrchestrator create(CollectingWriter collectingWriter) {
        PluginRegistry previewRegistry = new PluginRegistry();
        previewRegistry.registerWriter(CollectingWriter.TYPE, collectingWriter);
        return new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(previewRegistry),
                previewRegistry,
                connectionRegistry);
    }
}

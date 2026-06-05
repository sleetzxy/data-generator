package com.datagenerator.api.service;

import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchemaService {

    private final YamlConfigLoader configLoader;
    private final ConfigPathResolver pathResolver;

    public SchemaService(YamlConfigLoader configLoader, ConfigPathResolver pathResolver) {
        this.configLoader = configLoader;
        this.pathResolver = pathResolver;
    }

    public List<String> listSchemas() {
        return pathResolver.listYamlBasenames("schemas");
    }

    public SchemaDefinition getSchema(String name) {
        return configLoader.loadSchema("schemas/" + name + ".yaml");
    }
}

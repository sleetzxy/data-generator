package com.datagenerator.web.service;

import com.datagenerator.web.dto.SchemaFieldResponse;
import com.datagenerator.web.dto.SchemaResponse;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.FieldDefinition;
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

    public SchemaResponse getSchema(String name) {
        SchemaDefinition definition = configLoader.loadSchema("schemas/" + name + ".yaml");
        return toResponse(definition);
    }

    private SchemaResponse toResponse(SchemaDefinition definition) {
        SchemaResponse response = new SchemaResponse();
        response.setTable(definition.getTable());
        response.setConstraints(definition.getConstraints());
        response.setFields(definition.getFields().stream()
                .map(this::toFieldResponse)
                .toList());
        return response;
    }

    private SchemaFieldResponse toFieldResponse(FieldDefinition field) {
        return new SchemaFieldResponse(field.getName(), field.getType(), field.getGenerator());
    }
}

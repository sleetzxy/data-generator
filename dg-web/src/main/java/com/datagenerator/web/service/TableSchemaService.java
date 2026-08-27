package com.datagenerator.web.service;

import com.datagenerator.web.dto.SchemaFieldResponse;
import com.datagenerator.web.dto.TableSchemaResponse;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.FieldDefinition;
import com.datagenerator.core.model.TableSchema;
import com.datagenerator.core.model.YamlConfigLoader;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TableSchemaService {

    private final YamlConfigLoader configLoader;
    private final ConfigPathResolver pathResolver;

    public TableSchemaService(YamlConfigLoader configLoader, ConfigPathResolver pathResolver) {
        this.configLoader = configLoader;
        this.pathResolver = pathResolver;
    }

    public List<String> listSchemas() {
        return pathResolver.listYamlBasenames("schemas");
    }

    public TableSchemaResponse getSchema(String name) {
        TableSchema definition = configLoader.loadSchema("schemas/" + name + ".yaml");
        return toResponse(definition);
    }

    private TableSchemaResponse toResponse(TableSchema definition) {
        TableSchemaResponse response = new TableSchemaResponse();
        response.setTable(definition.getTable());
        response.setConstraints(definition.getConstraints());
        response.setFields(definition.getFields().stream()
                .map(this::toFieldResponse)
                .toList());
        return response;
    }

    private SchemaFieldResponse toFieldResponse(FieldDefinition field) {
        SchemaFieldResponse response = new SchemaFieldResponse(field.getName(), field.getType(), field.getGenerator());
        response.setPrimaryKey(field.isPrimaryKey());
        return response;
    }
}

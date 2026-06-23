package com.datagenerator.ai.port.http;

import com.datagenerator.ai.port.SchemaCatalogPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;

public class HttpSchemaCatalogPort implements SchemaCatalogPort {

    private static final String SCHEMAS_PATH = "/api/v1/schemas";

    private final HttpServiceClient webClient;

    public HttpSchemaCatalogPort(HttpServiceClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public List<String> listSchemas() {
        return webClient.get(SCHEMAS_PATH, new TypeReference<>() {});
    }

    @Override
    public SchemaDetail getSchema(String name) {
        SchemaDto schema = webClient.get(SCHEMAS_PATH + "/" + name.trim(), new TypeReference<>() {});
        List<SchemaField> fields = schema.fields() == null
                ? List.of()
                : schema.fields().stream()
                        .map(field -> new SchemaField(
                                field.name(),
                                field.type(),
                                field.primaryKey(),
                                field.generator()))
                        .toList();
        return new SchemaDetail(schema.table(), schema.constraints(), schema.seed(), fields);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SchemaDto(
            String table,
            String constraints,
            Map<String, Object> seed,
            List<SchemaFieldDto> fields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SchemaFieldDto(
            String name,
            String type,
            boolean primaryKey,
            Map<String, Object> generator) {}
}

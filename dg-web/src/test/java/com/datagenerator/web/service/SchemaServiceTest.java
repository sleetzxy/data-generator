package com.datagenerator.web.service;

import com.datagenerator.web.dto.SchemaResponse;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaServiceTest {

    @Test
    void getSchema_existingSchema_returnsDto() {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        ConfigPathResolver pathResolver = mock(ConfigPathResolver.class);
        SchemaService schemaService = new SchemaService(configLoader, pathResolver);

        SchemaDefinition definition = new SchemaDefinition();
        definition.setTable("customers");
        definition.setConstraints("constraints/customer.yaml");
        definition.setFields(List.of(new FieldDefinition("id", "long", Map.of("type", "sequence"))));
        when(configLoader.loadSchema("schemas/customer.yaml")).thenReturn(definition);

        SchemaResponse response = schemaService.getSchema("customer");

        assertThat(response.getTable()).isEqualTo("customers");
        assertThat(response.getConstraints()).isEqualTo("constraints/customer.yaml");
        assertThat(response.getFields()).hasSize(1);
        assertThat(response.getFields().getFirst().getName()).isEqualTo("id");
        assertThat(response.getFields().getFirst().getGenerator()).containsEntry("type", "sequence");
    }

    @Test
    void listSchemas_configDir_returnsBasenames() {
        YamlConfigLoader configLoader = mock(YamlConfigLoader.class);
        ConfigPathResolver pathResolver = mock(ConfigPathResolver.class);
        when(pathResolver.listYamlBasenames("schemas")).thenReturn(List.of("customer", "order"));
        SchemaService schemaService = new SchemaService(configLoader, pathResolver);

        assertThat(schemaService.listSchemas()).containsExactly("customer", "order");
    }
}

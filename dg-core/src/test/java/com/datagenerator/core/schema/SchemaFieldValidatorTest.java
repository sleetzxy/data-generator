package com.datagenerator.core.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaFieldValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"VARCHAR", "CHAR", "TEXT", "VARCHAR(64)", "nvarchar(100)", "STRING"})
    void isStringType_stringTypes_returnsTrue(String type) {
        assertThat(SchemaFieldValidator.isStringType(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BIGINT", "INT", "INTEGER", "DECIMAL", "TIMESTAMP", "GEOMETRY"})
    void isStringType_nonStringTypes_returnsFalse(String type) {
        assertThat(SchemaFieldValidator.isStringType(type)).isFalse();
    }

    @Test
    void validate_prefixOnVarchar_passes() {
        SchemaDefinition schema = schemaWithField(
                "order_id", "VARCHAR", Map.of("strategy", "sequence", "prefix", "ORD-"));
        assertThatCode(() -> SchemaFieldValidator.validate(schema)).doesNotThrowAnyException();
    }

    @Test
    void validate_prefixOnBigint_throws() {
        SchemaDefinition schema = schemaWithField(
                "order_id", "BIGINT", Map.of("strategy", "sequence", "prefix", "ORD-"));
        assertThatThrownBy(() -> SchemaFieldValidator.validate(schema))
                .isInstanceOf(ConfigLoadException.class)
                .hasMessageContaining("order_id")
                .hasMessageContaining("VARCHAR, CHAR, TEXT")
                .hasMessageContaining("BIGINT");
    }

    @Test
    void validate_noPrefix_skipsTypeCheck() {
        SchemaDefinition schema = schemaWithField(
                "id", "BIGINT", Map.of("strategy", "sequence", "start", 1));
        assertThatCode(() -> SchemaFieldValidator.validate(schema)).doesNotThrowAnyException();
    }

    private static SchemaDefinition schemaWithField(String name, String type, Map<String, Object> generator) {
        SchemaDefinition schema = new SchemaDefinition();
        schema.setTable("orders");
        schema.setFields(List.of(new FieldDefinition(name, type, generator)));
        return schema;
    }
}

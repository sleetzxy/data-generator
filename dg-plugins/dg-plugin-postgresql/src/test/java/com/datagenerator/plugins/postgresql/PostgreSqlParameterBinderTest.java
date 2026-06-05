package com.datagenerator.plugins.postgresql;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSqlParameterBinderTest {

    @Test
    void prepareValue_timestampString_convertsToLocalDateTime() {
        Object value = PostgreSqlParameterBinder.prepareValue("2024-11-14 14:15:53");

        assertThat(value).isEqualTo(LocalDateTime.parse("2024-11-14T14:15:53"));
    }

    @Test
    void prepareValue_timestampWithMillis_convertsToLocalDateTime() {
        Object value = PostgreSqlParameterBinder.prepareValue("2024-06-26 00:00:00.000");

        assertThat(value).isEqualTo(LocalDateTime.parse("2024-06-26T00:00:00"));
    }

    @Test
    void prepareValue_nonTimestampString_keepsOriginalValue() {
        Object value = PostgreSqlParameterBinder.prepareValue("440115");

        assertThat(value).isEqualTo("440115");
    }

    @Test
    void prepareValue_geomColumn_convertsWktToPgGeometry() throws Exception {
        Object value = PostgreSqlParameterBinder.prepareValue(
                "geom", "POINT(113.35665228525738 22.94114817393566)");

        assertThat(value).isInstanceOf(org.postgresql.util.PGobject.class);
        org.postgresql.util.PGobject geometry = (org.postgresql.util.PGobject) value;
        assertThat(geometry.getType()).isEqualTo("geometry");
        assertThat(geometry.getValue()).isEqualTo("POINT(113.35665228525738 22.94114817393566)");
    }

    @Test
    void prepareValue_geomwktColumn_keepsWktAsString() {
        Object value = PostgreSqlParameterBinder.prepareValue(
                "geomwkt", "POINT(113.35665228525738 22.94114817393566)");

        assertThat(value).isEqualTo("POINT(113.35665228525738 22.94114817393566)");
    }

    @Test
    void prepareValue_emptyString_convertsToNull() {
        Object value = PostgreSqlParameterBinder.prepareValue("mileage_end", "");

        assertThat(value).isNull();
    }

    @Test
    void prepareValue_numericValue_keepsOriginalValue() {
        Object value = PostgreSqlParameterBinder.prepareValue(0);

        assertThat(value).isEqualTo(0);
    }
}

package com.datagenerator.plugins.postgresql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSqlConnectionUrlsTest {

    @Test
    void withWriterDefaults_noQuery_appendsParam() {
        assertThat(PostgreSqlConnectionUrls.withWriterDefaults("jdbc:postgresql://host/db"))
                .isEqualTo("jdbc:postgresql://host/db?reWriteBatchedInserts=true");
    }

    @Test
    void withWriterDefaults_existingQuery_appendsWithAmpersand() {
        assertThat(PostgreSqlConnectionUrls.withWriterDefaults("jdbc:postgresql://host/db?sslmode=disable"))
                .isEqualTo("jdbc:postgresql://host/db?sslmode=disable&reWriteBatchedInserts=true");
    }

    @Test
    void withWriterDefaults_alreadySet_unchanged() {
        String url = "jdbc:postgresql://host/db?reWriteBatchedInserts=false";
        assertThat(PostgreSqlConnectionUrls.withWriterDefaults(url)).isSameAs(url);
    }
}

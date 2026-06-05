package com.datagenerator.plugins.autoconfig;

import com.datagenerator.plugins.clickhouse.ClickHouseReader;
import com.datagenerator.plugins.clickhouse.ClickHouseWriter;
import com.datagenerator.plugins.csv.CsvReader;
import com.datagenerator.plugins.csv.CsvWriter;
import com.datagenerator.plugins.postgresql.PostgreSqlReader;
import com.datagenerator.plugins.postgresql.PostgreSqlWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PluginsAutoConfiguration {

    @Bean
    public PostgreSqlReader postgreSqlReader() {
        return new PostgreSqlReader();
    }

    @Bean
    public PostgreSqlWriter postgreSqlWriter() {
        return new PostgreSqlWriter();
    }

    @Bean
    public ClickHouseReader clickHouseReader() {
        return new ClickHouseReader();
    }

    @Bean
    public ClickHouseWriter clickHouseWriter() {
        return new ClickHouseWriter();
    }

    @Bean
    public CsvReader csvReader() {
        return new CsvReader();
    }

    @Bean
    public CsvWriter csvWriter() {
        return new CsvWriter();
    }
}

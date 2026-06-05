package com.datagenerator.plugins.postgresql.autoconfig;

import com.datagenerator.plugins.postgresql.PostgreSqlReader;
import com.datagenerator.plugins.postgresql.PostgreSqlWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PostgreSqlPluginAutoConfiguration {

    @Bean
    public PostgreSqlReader postgreSqlReader() {
        return new PostgreSqlReader();
    }

    @Bean
    public PostgreSqlWriter postgreSqlWriter() {
        return new PostgreSqlWriter();
    }
}

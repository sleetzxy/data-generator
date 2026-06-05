package com.datagenerator.web.storage;

import com.datagenerator.web.config.DataGeneratorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class SqliteDataSourceConfig {

    @Bean
    DataSource dataSource(DataGeneratorProperties properties) throws IOException {
        Path dbPath = Path.of(properties.getStorage().getSqlitePath()).toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SqliteSchemaInitializer.initialize(jdbcTemplate);
        return jdbcTemplate;
    }
}

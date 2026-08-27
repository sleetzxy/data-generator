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
        migrateLegacySqliteFile(dbPath);
        Files.createDirectories(dbPath.getParent());
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath);
        return dataSource;
    }

    /** 旧版默认库名 {@code dg-jobs.db} 自动重命名为 {@code dg-tasks.db}。 */
    private static void migrateLegacySqliteFile(Path dbPath) throws IOException {
        if (Files.exists(dbPath)) {
            return;
        }
        Path legacyPath = dbPath.getParent().resolve("dg-jobs.db");
        if (Files.exists(legacyPath)) {
            Files.move(legacyPath, dbPath);
        }
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SqliteSchemaInitializer.initialize(jdbcTemplate);
        return jdbcTemplate;
    }
}

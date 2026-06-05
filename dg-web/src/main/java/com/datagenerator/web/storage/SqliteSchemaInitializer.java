package com.datagenerator.web.storage;

import org.springframework.jdbc.core.JdbcTemplate;

public final class SqliteSchemaInitializer {

    private SqliteSchemaInitializer() {
    }

    public static void initialize(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS jobs (
                    job_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    job_config TEXT,
                    submitted_at TEXT NOT NULL,
                    duration TEXT,
                    error_message TEXT,
                    total_tables INTEGER NOT NULL DEFAULT 0,
                    completed_tables INTEGER NOT NULL DEFAULT 0,
                    total_rows INTEGER NOT NULL DEFAULT 0,
                    written_rows INTEGER NOT NULL DEFAULT 0,
                    failed_rows INTEGER NOT NULL DEFAULT 0,
                    details_json TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_jobs_submitted_at
                ON jobs(submitted_at)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS job_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT NOT NULL,
                    logged_at TEXT NOT NULL,
                    level TEXT NOT NULL,
                    message TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_job_logs_job_id
                ON job_logs(job_id, id)
                """);
    }
}

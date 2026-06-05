package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobLogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class JobLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public JobLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(String jobId, String level, String message) {
        jdbcTemplate.update(
                "INSERT INTO job_logs (job_id, logged_at, level, message) VALUES (?, ?, ?, ?)",
                jobId,
                Instant.now().toString(),
                level,
                message);
    }

    public List<JobLogEntry> findByJobId(String jobId) {
        return jdbcTemplate.query(
                "SELECT logged_at, level, message FROM job_logs WHERE job_id = ? ORDER BY id ASC",
                (rs, rowNum) -> new JobLogEntry(
                        rs.getString("logged_at"),
                        rs.getString("level"),
                        rs.getString("message")),
                jobId);
    }

    public void deleteByJobId(String jobId) {
        jdbcTemplate.update("DELETE FROM job_logs WHERE job_id = ?", jobId);
    }
}

package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.TableDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {

    private static final TypeReference<List<TableDetail>> TABLE_DETAIL_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(JobResponse job) {
        JobProgress progress = progressOrEmpty(job);
        jdbcTemplate.update("""
                INSERT INTO jobs (
                    job_id, status, job_config, submitted_at, duration, error_message,
                    total_tables, completed_tables, total_rows, written_rows, failed_rows, details_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                job.getJobId(),
                job.getStatus().name(),
                job.getJobConfig(),
                job.getSubmittedAt(),
                job.getDuration(),
                job.getErrorMessage(),
                progress.getTotalTables(),
                progress.getCompletedTables(),
                progress.getTotalRows(),
                progress.getWrittenRows(),
                progress.getFailedRows(),
                serializeDetails(job.getDetails()));
    }

    public void update(JobResponse job) {
        JobProgress progress = progressOrEmpty(job);
        jdbcTemplate.update("""
                UPDATE jobs SET
                    status = ?,
                    job_config = ?,
                    submitted_at = ?,
                    duration = ?,
                    error_message = ?,
                    total_tables = ?,
                    completed_tables = ?,
                    total_rows = ?,
                    written_rows = ?,
                    failed_rows = ?,
                    details_json = ?
                WHERE job_id = ?
                """,
                job.getStatus().name(),
                job.getJobConfig(),
                job.getSubmittedAt(),
                job.getDuration(),
                job.getErrorMessage(),
                progress.getTotalTables(),
                progress.getCompletedTables(),
                progress.getTotalRows(),
                progress.getWrittenRows(),
                progress.getFailedRows(),
                serializeDetails(job.getDetails()),
                job.getJobId());
    }

    public Optional<JobResponse> findById(String jobId) {
        List<JobResponse> results = jdbcTemplate.query("""
                SELECT job_id, status, job_config, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows, details_json
                FROM jobs WHERE job_id = ?
                """, this::mapRow, jobId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<JobResponse> listAll() {
        return jdbcTemplate.query("""
                SELECT job_id, status, job_config, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows, details_json
                FROM jobs ORDER BY submitted_at DESC
                """, this::mapRow);
    }

    public List<JobResponse> findByStatusIn(List<JobStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", statuses.stream().map(status -> "?").toList());
        Object[] args = statuses.stream().map(JobStatus::name).toArray();
        return jdbcTemplate.query("""
                SELECT job_id, status, job_config, submitted_at, duration, error_message,
                       total_tables, completed_tables, total_rows, written_rows, failed_rows, details_json
                FROM jobs WHERE status IN (""" + placeholders + ")", this::mapRow, args);
    }

    public void delete(String jobId) {
        jdbcTemplate.update("DELETE FROM jobs WHERE job_id = ?", jobId);
    }

    private JobResponse mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        JobProgress progress = new JobProgress(
                rs.getInt("total_tables"),
                rs.getInt("completed_tables"),
                rs.getLong("total_rows"),
                rs.getLong("written_rows"),
                rs.getLong("failed_rows"));
        JobResponse response = new JobResponse(
                rs.getString("job_id"),
                JobStatus.valueOf(rs.getString("status")),
                progress,
                deserializeDetails(rs.getString("details_json")),
                rs.getString("duration"),
                rs.getString("job_config"),
                rs.getString("submitted_at"),
                rs.getString("error_message"),
                null);
        return response;
    }

    private static JobProgress progressOrEmpty(JobResponse job) {
        return job.getProgress() == null ? new JobProgress(0, 0, 0, 0, 0) : job.getProgress();
    }

    private String serializeDetails(List<TableDetail> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize job details", exception);
        }
    }

    private List<TableDetail> deserializeDetails(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, TABLE_DETAIL_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize job details", exception);
        }
    }
}

package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobLogEntry;
import com.datagenerator.web.dto.JobOptions;
import com.datagenerator.web.dto.JobProgress;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.web.dto.JobSummaryResponse;
import com.datagenerator.web.dto.PreviewOptions;
import com.datagenerator.web.dto.PreviewRequest;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.exception.JobNotFoundException;
import com.datagenerator.web.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.GenerationOptions;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.JobResult;
import com.datagenerator.core.engine.TableResult;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.OverridePathResolver;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int PREVIEW_MAX_LIMIT = 100;

    private final JobOrchestrator jobOrchestrator;
    private final PreviewJobOrchestratorFactory previewOrchestratorFactory;
    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;
    private final JobRuntimeSettings runtimeSettings;
    private final AsyncJobExecutor asyncJobExecutor;
    private final JobLogStore jobLogStore;
    private final ConcurrentHashMap<String, JobResponse> jobs = new ConcurrentHashMap<>();

    public JobService(
            JobOrchestrator jobOrchestrator,
            PreviewJobOrchestratorFactory previewOrchestratorFactory,
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            JobRuntimeSettings runtimeSettings,
            JobLogStore jobLogStore) {
        this.jobOrchestrator = jobOrchestrator;
        this.previewOrchestratorFactory = previewOrchestratorFactory;
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
        this.runtimeSettings = runtimeSettings;
        this.jobLogStore = jobLogStore;
        this.asyncJobExecutor = new AsyncJobExecutor(runtimeSettings.threadPoolSize(), jobs, jobLogStore);
    }

    public JobSubmitResult submit(JobSubmitRequest request) {
        validateJobConfig(request.getJobConfig());
        String jobId = generateJobId();
        String submittedAt = Instant.now().toString();
        JobDefinition job = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        long estimatedRows = estimateTotalRows(job);

        JobResponse placeholder = new JobResponse(
                jobId,
                JobStatus.PENDING,
                emptyProgress(),
                List.of(),
                null,
                request.getJobConfig(),
                submittedAt,
                null,
                null);
        jobs.put(jobId, placeholder);
        jobLogStore.info(jobId, "任务已提交，配置文件: " + request.getJobConfig());
        jobLogStore.info(jobId, "预估生成行数: " + estimatedRows);

        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (estimatedRows > syncThreshold) {
            log.info("Submitting async job {} (estimatedRows={}, threshold={})", jobId, estimatedRows, syncThreshold);
            jobLogStore.info(jobId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            asyncJobExecutor.submit(jobId, () -> executeAndStore(jobId, job, resolveWriter(job, request.getWriter()), options));
            JobResponse pending = jobs.get(jobId);
            return new JobSubmitResult(pending, true);
        }

        log.info("Submitting sync job {} (estimatedRows={})", jobId, estimatedRows);
        jobLogStore.info(jobId, "同步执行中");
        JobResponse response = executeAndStore(jobId, job, resolveWriter(job, request.getWriter()), options);
        return new JobSubmitResult(response, false);
    }

    public List<JobSummaryResponse> listAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(JobResponse::getSubmittedAt, Comparator.nullsLast(String::compareTo))
                        .reversed())
                .map(this::toSummary)
                .toList();
    }

    public JobResponse getById(String jobId) {
        JobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new JobNotFoundException(jobId);
        }
        return response;
    }

    public List<JobLogEntry> getLogs(String jobId) {
        if (!jobs.containsKey(jobId)) {
            throw new JobNotFoundException(jobId);
        }
        return jobLogStore.getLogs(jobId);
    }

    public void cancel(String jobId) {
        JobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new JobNotFoundException(jobId);
        }
        if (isTerminalStatus(response.getStatus())) {
            return;
        }
        if (!asyncJobExecutor.cancel(jobId)) {
            throw new IllegalArgumentException("Job cannot be cancelled in status: " + response.getStatus());
        }
    }

    private static boolean isTerminalStatus(JobStatus status) {
        return status == JobStatus.COMPLETED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }

    public void remove(String jobId) {
        JobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new JobNotFoundException(jobId);
        }
        if (response.getStatus() == JobStatus.PENDING || response.getStatus() == JobStatus.RUNNING) {
            throw new IllegalArgumentException("Running job cannot be removed: " + jobId);
        }
        jobs.remove(jobId);
        jobLogStore.remove(jobId);
    }

    public JobResponse preview(PreviewRequest request) {
        validateJobConfig(request.getJobConfig());
        long start = System.currentTimeMillis();

        JobDefinition job = loadAndApplyOverrides(request);
        JobDefinition previewJob = preparePreviewJob(job, request.getPreview());

        CollectingWriter collectingWriter = new CollectingWriter();
        JobOrchestrator previewOrchestrator = previewOrchestratorFactory.create(collectingWriter);

        GenerationOptions options = toGenerationOptions(request.getOptions());
        JobResult result = previewOrchestrator.run(
                previewJob,
                Map.of("type", CollectingWriter.TYPE),
                options);

        return toJobResponse(null, JobStatus.COMPLETED, result, start, null, null, null, collectingWriter.toRowMaps());
    }

    private JobResponse executeAndStore(
            String jobId,
            JobDefinition job,
            Map<String, Object> writer,
            GenerationOptions options) {
        long start = System.currentTimeMillis();
        JobResponse current = jobs.get(jobId);
        if (current != null) {
            current.setStatus(JobStatus.RUNNING);
        }
        jobLogStore.info(jobId, "开始生成数据，共 " + job.getTables().size() + " 张表");
        try {
            JobResult result = jobOrchestrator.run(job, writer, options);
            for (TableResult tableResult : result.details()) {
                jobLogStore.info(
                        jobId,
                        "表 " + tableResult.table() + " 完成: 写入 "
                                + tableResult.rows() + " 行, 失败 " + tableResult.failedRows() + " 行");
            }
            JobResponse response = toJobResponse(
                    jobId,
                    JobStatus.COMPLETED,
                    result,
                    start,
                    current == null ? null : current.getJobConfig(),
                    current == null ? null : current.getSubmittedAt(),
                    null,
                    null);
            jobs.put(jobId, response);
            jobLogStore.info(
                    jobId,
                    "任务完成，耗时 " + response.getDuration()
                            + "，共写入 " + result.writtenRows() + " 行");
            return response;
        } catch (Exception exception) {
            jobLogStore.error(jobId, "任务执行失败: " + exception.getMessage());
            JobResponse failed = current == null
                    ? new JobResponse(jobId, JobStatus.FAILED, emptyProgress(), List.of(), null, null, null, exception.getMessage(), null)
                    : current;
            failed.setStatus(JobStatus.FAILED);
            failed.setErrorMessage(exception.getMessage());
            failed.setDetails(List.of(new TableDetail("_error", 0, 0, exception.getMessage())));
            jobs.put(jobId, failed);
            throw exception;
        }
    }

    private JobDefinition loadAndApplyOverrides(JobSubmitRequest request) {
        JobDefinition job = configLoader.loadJob(request.getJobConfig());
        applyOverrides(job, request.getOverrides());
        return job;
    }

    /**
     * 请求体 writer 覆盖 Job YAML 中的 writer；二者至少配置一处。
     */
    private Map<String, Object> resolveWriter(JobDefinition job, Map<String, Object> requestWriter) {
        Map<String, Object> merged = new HashMap<>();
        if (requestWriter != null && !requestWriter.isEmpty()) {
            merged.putAll(requestWriter);
        }
        if (!job.getWriter().isEmpty()) {
            merged.putAll(job.getWriter());
        }
        validateWriterConfigured(job, merged);
        return merged;
    }

    private void validateWriterConfigured(JobDefinition job, Map<String, Object> defaultWriter) {
        for (TableTask table : job.getTables()) {
            Map<String, Object> effective = new HashMap<>(defaultWriter);
            effective.putAll(table.getWriter());
            if (effective.isEmpty()
                    || effective.get("type") == null
                    || String.valueOf(effective.get("type")).isBlank()) {
                throw new IllegalArgumentException(
                        "表 '" + table.getName() + "' 缺少 writer 配置，请在表级或 job 级指定");
            }
        }
    }

    private void applyOverrides(JobDefinition job, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            TableTask table = OverridePathResolver.resolveTable(job, entry.getKey());
            String field = OverridePathResolver.resolveField(table, entry.getKey());
            if ("count".equals(field)) {
                table.setCount(toLong(entry.getValue()));
            } else {
                throw new ConfigLoadException("Unsupported override field: " + field);
            }
        }
    }

    private JobDefinition preparePreviewJob(JobDefinition job, PreviewOptions previewOptions) {
        PreviewOptions options = previewOptions == null ? new PreviewOptions() : previewOptions;
        int limit = Math.min(Math.max(options.getLimit(), 1), PREVIEW_MAX_LIMIT);
        List<String> selectedTables = options.getTables();

        JobDefinition previewJob = new JobDefinition();
        previewJob.setJob(job.getJob());
        previewJob.setConstraints(job.getConstraints());
        previewJob.setInlineConstraints(new ArrayList<>(job.getInlineConstraints()));

        List<TableTask> tables = new ArrayList<>();
        for (TableTask tableTask : job.getTables()) {
            if (!selectedTables.isEmpty() && !selectedTables.contains(tableTask.getName())) {
                continue;
            }
            TableTask copy = copyTableTask(tableTask);
            copy.setCount(Math.min(copy.getCount(), limit));
            tables.add(copy);
        }
        previewJob.setTables(tables);
        return previewJob;
    }

    private TableTask copyTableTask(TableTask source) {
        TableTask copy = new TableTask();
        copy.setName(source.getName());
        copy.setSchema(source.getSchema());
        copy.setSchemaDefinition(source.getSchemaDefinition());
        copy.setCount(source.getCount());
        copy.setDependsOn(new ArrayList<>(source.getDependsOn()));
        copy.setConstraints(source.getConstraints());
        copy.setInlineConstraints(new ArrayList<>(source.getInlineConstraints()));
        return copy;
    }

    private JobResponse toJobResponse(
            String jobId,
            JobStatus status,
            JobResult result,
            long startMillis,
            String jobConfig,
            String submittedAt,
            String errorMessage,
            Map<String, List<Map<String, Object>>> rows) {
        List<TableDetail> details = result.details().stream()
                .map(this::toTableDetail)
                .toList();
        JobProgress progress = new JobProgress(
                details.size(),
                details.size(),
                result.totalRows(),
                result.writtenRows(),
                result.failedRows());
        return new JobResponse(
                jobId,
                status,
                progress,
                details,
                formatDuration(System.currentTimeMillis() - startMillis),
                jobConfig,
                submittedAt,
                errorMessage,
                rows);
    }

    private JobSummaryResponse toSummary(JobResponse response) {
        JobProgress progress = response.getProgress();
        long totalRows = progress == null ? 0 : progress.getTotalRows();
        long writtenRows = progress == null ? 0 : progress.getWrittenRows();
        return new JobSummaryResponse(
                response.getJobId(),
                response.getJobConfig(),
                response.getStatus(),
                response.getSubmittedAt(),
                response.getDuration(),
                totalRows,
                writtenRows,
                response.getErrorMessage());
    }

    private TableDetail toTableDetail(TableResult tableResult) {
        return new TableDetail(
                tableResult.table(),
                tableResult.rows(),
                tableResult.failedRows(),
                tableResult.status());
    }

    private GenerationOptions toGenerationOptions(JobOptions options) {
        int batchSize = runtimeSettings.batchSize();
        int maxRetries = GenerationOptions.DEFAULT_MAX_RETRIES;
        String onConstraintFail = GenerationOptions.DEFAULT_ON_FAIL;
        if (options != null) {
            if (options.getBatchSize() != null && options.getBatchSize() > 0) {
                batchSize = options.getBatchSize();
            }
            if (options.getMaxRetries() != null && options.getMaxRetries() >= 0) {
                maxRetries = options.getMaxRetries();
            }
            if (options.getOnConstraintFail() != null && !options.getOnConstraintFail().isBlank()) {
                onConstraintFail = options.getOnConstraintFail();
            }
        }
        return new GenerationOptions(batchSize, maxRetries, onConstraintFail);
    }

    private int resolveSyncThreshold(JobOptions options) {
        if (options != null && options.getSyncThreshold() != null && options.getSyncThreshold() > 0) {
            return options.getSyncThreshold();
        }
        return runtimeSettings.syncThreshold();
    }

    private static long estimateTotalRows(JobDefinition job) {
        return job.getTables().stream().mapToLong(TableTask::getCount).sum();
    }

    private void validateJobConfig(String jobConfig) {
        if (jobConfig == null || jobConfig.isBlank()) {
            throw new IllegalArgumentException("jobConfig is required");
        }
    }

    private String generateJobId() {
        return "job-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ConfigLoadException("Override value must be numeric: " + value);
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format("%.1fs", millis / 1000.0);
    }

    private static JobProgress emptyProgress() {
        return new JobProgress(0, 0, 0, 0, 0);
    }
}

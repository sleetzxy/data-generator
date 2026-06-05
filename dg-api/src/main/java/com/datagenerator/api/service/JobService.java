package com.datagenerator.api.service;

import com.datagenerator.api.config.JobRuntimeSettings;
import com.datagenerator.api.dto.JobOptions;
import com.datagenerator.api.dto.JobProgress;
import com.datagenerator.api.dto.JobResponse;
import com.datagenerator.api.dto.JobStatus;
import com.datagenerator.api.dto.JobSubmitRequest;
import com.datagenerator.api.dto.JobSubmitResult;
import com.datagenerator.api.dto.PreviewOptions;
import com.datagenerator.api.dto.PreviewRequest;
import com.datagenerator.api.dto.TableDetail;
import com.datagenerator.api.exception.JobNotFoundException;
import com.datagenerator.api.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.GenerationOptions;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.JobResult;
import com.datagenerator.core.engine.PluginRegistry;
import com.datagenerator.core.engine.TableGenerator;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int PREVIEW_MAX_LIMIT = 100;

    private final JobOrchestrator jobOrchestrator;
    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;
    private final JobRuntimeSettings runtimeSettings;
    private final AsyncJobExecutor asyncJobExecutor;
    private final ConcurrentHashMap<String, JobResponse> jobs = new ConcurrentHashMap<>();

    public JobService(
            JobOrchestrator jobOrchestrator,
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            JobRuntimeSettings runtimeSettings) {
        this.jobOrchestrator = jobOrchestrator;
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
        this.runtimeSettings = runtimeSettings;
        this.asyncJobExecutor = new AsyncJobExecutor(runtimeSettings.threadPoolSize(), jobs);
    }

    public JobSubmitResult submit(JobSubmitRequest request) {
        validateJobConfig(request.getJobConfig());
        String jobId = generateJobId();
        JobDefinition job = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        long estimatedRows = estimateTotalRows(job);

        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (estimatedRows > syncThreshold) {
            log.info("Submitting async job {} (estimatedRows={}, threshold={})", jobId, estimatedRows, syncThreshold);
            asyncJobExecutor.submit(jobId, () -> executeAndStore(jobId, job, request.getWriter(), options));
            JobResponse pending = jobs.get(jobId);
            return new JobSubmitResult(pending, true);
        }

        log.info("Submitting sync job {} (estimatedRows={})", jobId, estimatedRows);
        JobResponse response = executeAndStore(jobId, job, request.getWriter(), options);
        return new JobSubmitResult(response, false);
    }

    public JobResponse getById(String jobId) {
        JobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new JobNotFoundException(jobId);
        }
        return response;
    }

    public void cancel(String jobId) {
        JobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new JobNotFoundException(jobId);
        }
        if (!asyncJobExecutor.cancel(jobId)) {
            throw new IllegalArgumentException("Job cannot be cancelled in status: " + response.getStatus());
        }
    }

    public JobResponse preview(PreviewRequest request) {
        validateJobConfig(request.getJobConfig());
        long start = System.currentTimeMillis();

        JobDefinition job = loadAndApplyOverrides(request);
        JobDefinition previewJob = preparePreviewJob(job, request.getPreview());

        CollectingWriter collectingWriter = new CollectingWriter();
        PluginRegistry previewRegistry = new PluginRegistry();
        previewRegistry.registerWriter(CollectingWriter.TYPE, collectingWriter);

        JobOrchestrator previewOrchestrator = new JobOrchestrator(
                configLoader,
                constraintLoader,
                new TableGenerator(previewRegistry),
                previewRegistry,
                connectionRegistry);

        GenerationOptions options = toGenerationOptions(request.getOptions());
        JobResult result = previewOrchestrator.run(
                previewJob,
                Map.of("type", CollectingWriter.TYPE),
                options);

        return toJobResponse(null, JobStatus.COMPLETED, result, start, collectingWriter.toRowMaps());
    }

    private JobResponse executeAndStore(
            String jobId,
            JobDefinition job,
            Map<String, Object> writer,
            GenerationOptions options) {
        long start = System.currentTimeMillis();
        JobResult result = jobOrchestrator.run(job, writer, options);
        JobResponse response = toJobResponse(jobId, JobStatus.COMPLETED, result, start, null);
        jobs.put(jobId, response);
        return response;
    }

    private JobDefinition loadAndApplyOverrides(JobSubmitRequest request) {
        JobDefinition job = configLoader.loadJob(request.getJobConfig());
        applyOverrides(job, request.getOverrides());
        return job;
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
        copy.setCount(source.getCount());
        copy.setDependsOn(new ArrayList<>(source.getDependsOn()));
        copy.setConstraints(source.getConstraints());
        return copy;
    }

    private JobResponse toJobResponse(
            String jobId,
            JobStatus status,
            JobResult result,
            long startMillis,
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
                rows);
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
}

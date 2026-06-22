package com.datagenerator.web.service;

import com.datagenerator.web.config.JobRuntimeSettings;
import com.datagenerator.web.dto.JobListResponse;
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
import com.datagenerator.web.dto.PreviewResponse;
import com.datagenerator.web.dto.PreviewTableResponse;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.exception.JobNotFoundException;
import com.datagenerator.web.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.config.WriterConfigResolver;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.GenerationOptions;
import com.datagenerator.core.engine.JobCancelledException;
import com.datagenerator.core.engine.JobExecutionListener;
import com.datagenerator.core.engine.JobOrchestrator;
import com.datagenerator.core.engine.JobResult;
import com.datagenerator.core.engine.TableResult;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.FieldDefinition;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.OverridePathResolver;
import com.datagenerator.core.schema.SchemaDefinition;
import com.datagenerator.core.schema.TableTask;
import com.datagenerator.core.schema.YamlConfigLoader;
import com.datagenerator.web.storage.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final int PREVIEW_MAX_LIMIT = 100;
    private static final int MAX_PAGE_SIZE = 200;

    private final JobOrchestrator jobOrchestrator;
    private final PreviewJobOrchestratorFactory previewOrchestratorFactory;
    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;
    private final JobRuntimeSettings runtimeSettings;
    private final AsyncJobExecutor asyncJobExecutor;
    private final JobLogStore jobLogStore;
    private final JobRepository jobRepository;
    private final JobCancellationRegistry cancellationRegistry;
    private final JobScheduleExecutor scheduleExecutor;

    public JobService(
            JobOrchestrator jobOrchestrator,
            PreviewJobOrchestratorFactory previewOrchestratorFactory,
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            JobRuntimeSettings runtimeSettings,
            JobRepository jobRepository,
            JobLogStore jobLogStore,
            AsyncJobExecutor asyncJobExecutor,
            JobCancellationRegistry cancellationRegistry,
            @Lazy JobScheduleExecutor scheduleExecutor) {
        this.jobOrchestrator = jobOrchestrator;
        this.previewOrchestratorFactory = previewOrchestratorFactory;
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
        this.runtimeSettings = runtimeSettings;
        this.jobRepository = jobRepository;
        this.jobLogStore = jobLogStore;
        this.asyncJobExecutor = asyncJobExecutor;
        this.cancellationRegistry = cancellationRegistry;
        this.scheduleExecutor = scheduleExecutor;
    }

    public JobSubmitResult submit(JobSubmitRequest request) {
        return scheduleExecutor.enqueue(request.getJobConfig(), TriggerSource.MANUAL, request);
    }

    public JobResponse createQueuedJob(String configPath, TriggerSource triggerSource) {
        validateJobConfig(configPath);
        String jobId = generateJobId();
        String submittedAt = Instant.now().toString();
        JobResponse placeholder = new JobResponse(
                jobId,
                JobStatus.PENDING,
                emptyProgress(),
                List.of(),
                null,
                configPath,
                submittedAt,
                null,
                null);
        placeholder.setTriggerSource(triggerSource);
        jobRepository.insert(placeholder);
        jobLogStore.info(jobId, "已加入队列");
        return placeholder;
    }

    public void executeAccepted(String jobId, JobSubmitRequest request) {
        JobResponse stored = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        JobDefinition job = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        List<Map<String, Object>> writers = resolveRuntimeWriters(job, request);
        long estimatedRows = estimateTotalRows(job);

        jobLogStore.info(jobId, "任务已提交，配置文件: " + request.getJobConfig());
        jobLogStore.info(jobId, "预估生成行数: " + estimatedRows);

        boolean forceAsync = stored.getTriggerSource() == TriggerSource.SCHEDULED;
        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (forceAsync || estimatedRows > syncThreshold) {
            log.info("Executing accepted job {} async (forceAsync={}, estimatedRows={}, threshold={})",
                    jobId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                jobLogStore.info(jobId, "定时触发，转为异步执行");
            } else {
                jobLogStore.info(jobId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncJobExecutor.submit(jobId, () -> executeAndStore(jobId, job, writers, options));
            return;
        }

        log.info("Executing accepted job {} sync (estimatedRows={})", jobId, estimatedRows);
        jobLogStore.info(jobId, "同步执行中");
        executeAndStore(jobId, job, writers, options);
    }

    JobSubmitResult doSubmit(JobSubmitRequest request, TriggerSource triggerSource) {
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
        placeholder.setTriggerSource(triggerSource);
        jobRepository.insert(placeholder);
        jobLogStore.info(jobId, "任务已提交，配置文件: " + request.getJobConfig());
        jobLogStore.info(jobId, "预估生成行数: " + estimatedRows);

        boolean forceAsync = triggerSource == TriggerSource.SCHEDULED;
        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (forceAsync || estimatedRows > syncThreshold) {
            log.info("Submitting async job {} (forceAsync={}, estimatedRows={}, threshold={})",
                    jobId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                jobLogStore.info(jobId, "定时触发，转为异步执行");
            } else {
                jobLogStore.info(jobId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncJobExecutor.submit(jobId, () -> executeAndStore(jobId, job, resolveRuntimeWriters(job, request), options));
            JobResponse pending = jobRepository.findById(jobId).orElseThrow();
            return new JobSubmitResult(pending, true);
        }

        log.info("Submitting sync job {} (estimatedRows={})", jobId, estimatedRows);
        jobLogStore.info(jobId, "同步执行中");
        JobResponse response = executeAndStore(jobId, job, resolveRuntimeWriters(job, request), options);
        return new JobSubmitResult(response, false);
    }

    public JobListResponse list(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        long total = jobRepository.countAll();
        List<JobSummaryResponse> items = jobRepository.listPage(offset, safeSize).stream()
                .map(this::toSummary)
                .toList();
        return new JobListResponse(items, total, safePage, safeSize);
    }

    public JobResponse getById(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    public List<JobLogEntry> getLogs(String jobId) {
        if (jobRepository.findById(jobId).isEmpty()) {
            throw new JobNotFoundException(jobId);
        }
        return jobLogStore.getLogs(jobId);
    }

    public void cancel(String jobId) {
        JobResponse response = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (isTerminalStatus(response.getStatus())) {
            return;
        }
        String jobConfig = response.getJobConfig();
        cancellationRegistry.markCancelled(jobId);
        boolean asyncCancelled = asyncJobExecutor.cancel(jobId);
        boolean interrupted = cancellationRegistry.interruptRunning(jobId);
        if (asyncCancelled || interrupted) {
            if (!asyncCancelled) {
                JobResponse current = jobRepository.findById(jobId).orElseThrow();
                if (!isTerminalStatus(current.getStatus())) {
                    current.setStatus(JobStatus.CANCELLED);
                    jobRepository.update(current);
                    jobLogStore.warn(jobId, "任务已被用户取消");
                }
            }
            scheduleExecutor.onJobTerminal(jobConfig);
            return;
        }
        throw new IllegalArgumentException("Job cannot be cancelled in status: " + response.getStatus());
    }

    private static boolean isTerminalStatus(JobStatus status) {
        return status == JobStatus.COMPLETED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }

    @Transactional
    public void remove(String jobId) {
        JobResponse response = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (response.getStatus() == JobStatus.PENDING || response.getStatus() == JobStatus.RUNNING) {
            throw new IllegalArgumentException("Running job cannot be removed: " + jobId);
        }
        jobLogStore.remove(jobId);
        jobRepository.delete(jobId);
    }

    public PreviewResponse preview(PreviewRequest request) {
        validateJobConfig(request.getJobConfig());
        long start = System.currentTimeMillis();

        JobDefinition job = loadAndApplyOverrides(request);
        JobDefinition previewJob = preparePreviewJob(job, request.getPreview());

        CollectingWriter collectingWriter = new CollectingWriter();
        JobOrchestrator previewOrchestrator = previewOrchestratorFactory.create(collectingWriter);

        GenerationOptions options = toGenerationOptions(request.getOptions());
        JobResult jobResult = previewOrchestrator.run(
                previewJob,
                Map.of("type", CollectingWriter.TYPE),
                options);
        validatePreviewResults(previewJob, jobResult);

        PreviewResponse response = new PreviewResponse();
        response.setStatus(JobStatus.COMPLETED);
        response.setDuration(formatDuration(System.currentTimeMillis() - start));
        response.setTables(buildPreviewTables(previewJob, collectingWriter.toRowMaps()));
        return response;
    }

    private void validatePreviewResults(JobDefinition previewJob, JobResult jobResult) {
        for (TableResult detail : jobResult.details()) {
            if (detail.rows() > 0) {
                continue;
            }
            throw new IllegalStateException(
                    "表 '" + detail.table() + "' 预览未生成任何行，请检查 seed 数据源连接与查询是否返回数据");
        }
    }

    private JobResponse executeAndStore(
            String jobId,
            JobDefinition job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        long start = System.currentTimeMillis();
        JobResponse current = jobRepository.findById(jobId).orElse(null);
        String jobConfig = current == null ? null : current.getJobConfig();
        String submittedAt = current == null ? null : current.getSubmittedAt();
        cancellationRegistry.registerRunning(jobId);
        int totalTables = job.getTables().size();
        long totalRows = estimateTotalRows(job);
        Map<String, TableDetail> runningDetails = new LinkedHashMap<>();
        try {
            if (current != null) {
                current.setStatus(JobStatus.RUNNING);
                current.setProgress(new JobProgress(totalTables, 0, totalRows, 0, 0));
                jobRepository.update(current);
            }
            jobLogStore.info(jobId, "开始生成数据，共 " + totalTables + " 张表，目标 " + totalRows + " 行");
            logJobContext(jobId, job, runtimeWriters, options);
            JobExecutionListener progressListener = createProgressListener(
                    jobId, jobConfig, submittedAt, totalTables, totalRows, runningDetails);
            GenerationOptions executionOptions =
                    options.withCancellationChecker(() -> isJobCancelled(jobId));
            JobResult result = jobOrchestrator.run(job, runtimeWriters, executionOptions, progressListener);
            for (TableResult tableResult : result.details()) {
                jobLogStore.info(
                        jobId,
                        "表 [" + tableResult.table() + "] 完成: 写入 "
                                + tableResult.rows() + " 行, 失败 " + tableResult.failedRows() + " 行, 状态 "
                                + tableResult.status());
            }
            if (isJobCancelled(jobId)) {
                jobLogStore.warn(jobId, "任务执行完毕但已被取消，保持 CANCELLED 状态");
                return jobRepository.findById(jobId).orElseThrow(
                        () -> new IllegalStateException("Job not found: " + jobId));
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
            jobRepository.update(response);
            jobLogStore.info(
                    jobId,
                    "任务完成，耗时 " + response.getDuration()
                            + "，共写入 " + result.writtenRows() + " 行");
            return response;
        } catch (JobCancelledException cancelled) {
            JobResponse latest = jobRepository.findById(jobId).orElseThrow(
                    () -> new IllegalStateException("Job not found: " + jobId));
            if (!isTerminalStatus(latest.getStatus())) {
                latest.setStatus(JobStatus.CANCELLED);
                jobRepository.update(latest);
                jobLogStore.warn(jobId, "任务已被用户取消");
            }
            return latest;
        } catch (Exception exception) {
            if (isJobCancelled(jobId)) {
                throw exception;
            }
            jobLogStore.error(jobId, "任务执行失败: " + exception.getClass().getSimpleName() + " - " + exception.getMessage());
            if (exception.getCause() != null && exception.getCause().getMessage() != null) {
                jobLogStore.error(jobId, "根本原因: " + exception.getCause().getMessage());
            }
            JobResponse failed = current == null
                    ? new JobResponse(jobId, JobStatus.FAILED, emptyProgress(), List.of(), null, null, null, exception.getMessage(), null)
                    : current;
            failed.setStatus(JobStatus.FAILED);
            failed.setErrorMessage(exception.getMessage());
            failed.setDetails(List.of(new TableDetail("_error", 0, 0, exception.getMessage())));
            jobRepository.update(failed);
            throw exception;
        } finally {
            cancellationRegistry.unregisterRunning(jobId);
            if (jobConfig != null) {
                scheduleExecutor.onJobTerminal(jobConfig);
            }
        }
    }

    private JobDefinition loadAndApplyOverrides(JobSubmitRequest request) {
        JobDefinition job = configLoader.loadJob(request.getJobConfig());
        applyOverrides(job, request.getOverrides());
        return job;
    }

    /**
     * 请求体 writer/writers 作为运行时默认值；Job YAML 中的 writer/writers 优先。
     */
    private List<Map<String, Object>> resolveRuntimeWriters(JobDefinition job, JobSubmitRequest request) {
        List<Map<String, Object>> runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriters());
        if (runtimeWriters.isEmpty()) {
            runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriter());
        }
        validateWriterConfigured(job, runtimeWriters);
        return runtimeWriters;
    }

    private void validateWriterConfigured(JobDefinition job, List<Map<String, Object>> runtimeWriters) {
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(job, runtimeWriters);
        for (TableTask table : job.getTables()) {
            List<Map<String, Object>> effective =
                    WriterConfigResolver.resolveTableWriters(table, defaultWriters);
            WriterConfigResolver.validateWriterMapsConfigured(table.getName(), effective);
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
        previewJob.setName(job.getName());
        previewJob.setConstraints(job.getConstraints());
        previewJob.setInlineConstraints(new ArrayList<>(job.getInlineConstraints()));
        previewJob.setSeeds(new ArrayList<>(job.getSeeds()));
        previewJob.setWriter(Map.of());
        previewJob.setWriters(List.of());

        List<TableTask> tables = new ArrayList<>();
        for (TableTask tableTask : job.getTables()) {
            if (!selectedTables.isEmpty() && !selectedTables.contains(tableTask.getName())) {
                continue;
            }
            TableTask copy = copyTableTask(tableTask);
            copy.setCount(Math.min(copy.getCount(), limit));
            copy.setWriter(Map.of());
            copy.setWriters(List.of());
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
        copy.setWriter(new HashMap<>(source.getWriter()));
        copy.setWriters(new ArrayList<>(source.getWriters()));
        return copy;
    }

    private List<PreviewTableResponse> buildPreviewTables(
            JobDefinition previewJob,
            Map<String, List<Map<String, Object>>> rowMaps) {
        List<PreviewTableResponse> tables = new ArrayList<>();
        for (TableTask tableTask : previewJob.getTables()) {
            SchemaDefinition schema = resolveSchema(tableTask);
            List<String> columns = schema.getFields().stream()
                    .map(FieldDefinition::getName)
                    .toList();
            PreviewTableResponse table = new PreviewTableResponse();
            table.setTableName(tableTask.getName());
            table.setSchemaTable(schema.getTable());
            table.setColumns(columns);
            table.setRows(resolvePreviewRows(tableTask, schema, rowMaps));
            tables.add(table);
        }
        return tables;
    }

    private List<Map<String, Object>> resolvePreviewRows(
            TableTask tableTask,
            SchemaDefinition schema,
            Map<String, List<Map<String, Object>>> rowMaps) {
        String schemaTable = schema.getTable();
        if (schemaTable != null && !schemaTable.isBlank() && rowMaps.containsKey(schemaTable)) {
            return rowMaps.get(schemaTable);
        }
        return rowMaps.getOrDefault(tableTask.getName(), List.of());
    }

    private SchemaDefinition resolveSchema(TableTask tableTask) {
        if (tableTask.getSchemaDefinition() != null) {
            return tableTask.getSchemaDefinition();
        }
        if (tableTask.getSchema() == null || tableTask.getSchema().isBlank()) {
            throw new ConfigLoadException("Table '" + tableTask.getName() + "' has no schema defined");
        }
        return configLoader.loadSchema(tableTask.getSchema());
    }

    private JobExecutionListener createProgressListener(
            String jobId,
            String jobConfig,
            String submittedAt,
            int totalTables,
            long totalRows,
            Map<String, TableDetail> runningDetails) {
        return new JobExecutionListener() {
            private final long[] jobWrittenRows = {0};
            private final long[] jobFailedRows = {0};
            private long lastLoggedJobWrittenRows = 0;
            private long lastProgressPersistMs = 0;
            private int batchesSincePersist = 0;
            private static final long PROGRESS_THROTTLE_MS = 3_000;
            private static final int PROGRESS_BATCH_INTERVAL = 10;

            @Override
            public void onTableStarted(String tableName, int tableIndex, int totalTables, long plannedRows) {
                int completedTables = countFinishedTables(runningDetails);
                runningDetails.put(tableName, new TableDetail(tableName, 0, 0, "running"));
                jobLogStore.info(
                        jobId,
                        "开始生成表 [" + tableName + "]（" + (tableIndex + 1) + "/" + totalTables + "），目标 "
                                + plannedRows + " 行");
                persistRunningProgress(
                        jobId,
                        jobConfig,
                        submittedAt,
                        totalTables,
                        completedTables,
                        totalRows,
                        jobWrittenRows[0],
                        jobFailedRows[0],
                        runningDetails);
            }

            @Override
            public void onBatchWritten(
                    String tableName,
                    int batchWritten,
                    int batchFailed,
                    long tableWrittenRows,
                    long tableFailedRows,
                    long jobWrittenRows,
                    long jobFailedRows) {
                this.jobWrittenRows[0] = jobWrittenRows;
                this.jobFailedRows[0] = jobFailedRows;
                runningDetails.put(
                        tableName, new TableDetail(tableName, tableWrittenRows, tableFailedRows, "running"));
                batchesSincePersist++;
                if (shouldPersistProgress()) {
                    long recentWritten = jobWrittenRows - lastLoggedJobWrittenRows;
                    jobLogStore.info(
                            jobId,
                            "表 [" + tableName + "] 近期写入 " + recentWritten + " 行（"
                                    + batchesSincePersist + " 批），任务累计 "
                                    + jobWrittenRows + " / " + totalRows + " 行");
                    persistRunningProgress(
                            jobId,
                            jobConfig,
                            submittedAt,
                            totalTables,
                            countFinishedTables(runningDetails),
                            totalRows,
                            jobWrittenRows,
                            jobFailedRows,
                            runningDetails);
                    lastLoggedJobWrittenRows = jobWrittenRows;
                    lastProgressPersistMs = System.currentTimeMillis();
                    batchesSincePersist = 0;
                }
            }

            private boolean shouldPersistProgress() {
                if (batchesSincePersist >= PROGRESS_BATCH_INTERVAL) {
                    return true;
                }
                long now = System.currentTimeMillis();
                return now - lastProgressPersistMs >= PROGRESS_THROTTLE_MS;
            }

            @Override
            public void onTableCompleted(
                    String tableName,
                    long tableWrittenRows,
                    long tableFailedRows,
                    int completedTables,
                    int totalTables,
                    long jobWrittenRows,
                    long jobFailedRows) {
                this.jobWrittenRows[0] = jobWrittenRows;
                this.jobFailedRows[0] = jobFailedRows;
                String status = tableFailedRows > 0 ? "partial" : "ok";
                runningDetails.put(tableName, new TableDetail(tableName, tableWrittenRows, tableFailedRows, status));
                jobLogStore.info(
                        jobId,
                        "表 [" + tableName + "] 完成: 写入 " + tableWrittenRows + " 行, 失败 "
                                + tableFailedRows + " 行");
                persistRunningProgress(
                        jobId,
                        jobConfig,
                        submittedAt,
                        totalTables,
                        completedTables,
                        totalRows,
                        jobWrittenRows,
                        jobFailedRows,
                        runningDetails);
                lastProgressPersistMs = System.currentTimeMillis();
                batchesSincePersist = 0;
            }
        };
    }

    private static int countFinishedTables(Map<String, TableDetail> runningDetails) {
        int completed = 0;
        for (TableDetail detail : runningDetails.values()) {
            if (!"running".equals(detail.getStatus())) {
                completed++;
            }
        }
        return completed;
    }

    private void persistRunningProgress(
            String jobId,
            String jobConfig,
            String submittedAt,
            int totalTables,
            int completedTables,
            long totalRows,
            long writtenRows,
            long failedRows,
            Map<String, TableDetail> runningDetails) {
        if (jobConfig == null) {
            return;
        }
        JobResponse response = new JobResponse(
                jobId,
                JobStatus.RUNNING,
                new JobProgress(totalTables, completedTables, totalRows, writtenRows, failedRows),
                new ArrayList<>(runningDetails.values()),
                null,
                jobConfig,
                submittedAt,
                null,
                null);
        jobRepository.update(response);
    }

    private void logJobContext(
            String jobId,
            JobDefinition job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(job, runtimeWriters);
        jobLogStore.info(jobId, "Writer 配置: " + summarizeWriters(defaultWriters));
        jobLogStore.info(
                jobId,
                "生成选项: batchSize=" + options.batchSize()
                        + ", maxRetries=" + options.maxRetries()
                        + ", onConstraintFail=" + options.onConstraintFail());
        jobLogStore.info(jobId, "Job seeds 数量: " + job.getSeeds().size());
        for (TableTask table : job.getTables()) {
            String schemaRef = table.getSchemaDefinition() != null
                    ? "inline"
                    : table.getSchema();
            jobLogStore.info(
                    jobId,
                    "表 [" + table.getName() + "] count=" + table.getCount()
                            + ", schema=" + schemaRef
                            + ", depends_on=" + table.getDependsOn()
                            + ", writer=" + summarizeWriters(
                                    WriterConfigResolver.resolveTableWriters(table, defaultWriters)));
        }
    }

    private static String summarizeWriters(List<Map<String, Object>> writers) {
        if (writers == null || writers.isEmpty()) {
            return "[]";
        }
        if (writers.size() == 1) {
            return summarizeMap(writers.getFirst());
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < writers.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(summarizeMap(writers.get(index)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String summarizeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        builder.append('}');
        return builder.toString();
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
        return new GenerationOptions(batchSize, maxRetries, onConstraintFail, runtimeSettings.threadPoolSize());
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

    private boolean isJobCancelled(String jobId) {
        if (cancellationRegistry.isCancelled(jobId) || asyncJobExecutor.isCancelled(jobId)) {
            return true;
        }
        return jobRepository.findById(jobId)
                .map(job -> job.getStatus() == JobStatus.CANCELLED)
                .orElse(false);
    }
}

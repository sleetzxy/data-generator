package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunListResponse;
import com.datagenerator.web.dto.TaskRunLogEntry;
import com.datagenerator.web.dto.TaskRunOptions;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.web.dto.TaskRunSummaryResponse;
import com.datagenerator.web.dto.PreviewOptions;
import com.datagenerator.web.dto.PreviewRequest;
import com.datagenerator.web.dto.PreviewResponse;
import com.datagenerator.web.dto.PreviewTableResponse;
import com.datagenerator.web.dto.TableDetail;
import com.datagenerator.web.dto.TriggerSource;
import com.datagenerator.web.exception.TaskRunNotFoundException;
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
import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.FieldDefinition;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.OverridePathResolver;
import com.datagenerator.core.model.TableSchema;
import com.datagenerator.core.model.SeedDefinition;
import com.datagenerator.core.model.TableTask;
import com.datagenerator.spi.model.ReaderConfig;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.web.storage.TaskRunLogRepository;
import com.datagenerator.web.storage.TaskRunRepository;
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
public class TaskRunService {

    private static final Logger log = LoggerFactory.getLogger(TaskRunService.class);
    private static final int PREVIEW_MAX_LIMIT = 100;
    private static final int MAX_PAGE_SIZE = 200;

    private final JobOrchestrator jobOrchestrator;
    private final PreviewOrchestratorFactory previewOrchestratorFactory;
    private final YamlConfigLoader configLoader;
    private final ConstraintLoader constraintLoader;
    private final ConnectionRegistry connectionRegistry;
    private final TaskRunRuntimeSettings runtimeSettings;
    private final AsyncTaskRunExecutor asyncTaskRunExecutor;
    private final TaskRunLogRepository taskRunLogRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunCancellationRegistry cancellationRegistry;
    private final TaskRunQueueExecutor scheduleExecutor;

    public TaskRunService(
            JobOrchestrator jobOrchestrator,
            PreviewOrchestratorFactory previewOrchestratorFactory,
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            TaskRunRuntimeSettings runtimeSettings,
            TaskRunRepository taskRunRepository,
            TaskRunLogRepository taskRunLogRepository,
            AsyncTaskRunExecutor asyncTaskRunExecutor,
            TaskRunCancellationRegistry cancellationRegistry,
            @Lazy TaskRunQueueExecutor scheduleExecutor) {
        this.jobOrchestrator = jobOrchestrator;
        this.previewOrchestratorFactory = previewOrchestratorFactory;
        this.configLoader = configLoader;
        this.constraintLoader = constraintLoader;
        this.connectionRegistry = connectionRegistry;
        this.runtimeSettings = runtimeSettings;
        this.taskRunRepository = taskRunRepository;
        this.taskRunLogRepository = taskRunLogRepository;
        this.asyncTaskRunExecutor = asyncTaskRunExecutor;
        this.cancellationRegistry = cancellationRegistry;
        this.scheduleExecutor = scheduleExecutor;
    }

    public TaskRunSubmitResult submit(TaskRunSubmitRequest request) {
        return scheduleExecutor.enqueue(request.getConfigPath(), TriggerSource.MANUAL, request);
    }

    public TaskRunResponse createQueuedJob(String configPath, TriggerSource triggerSource) {
        validateConfigPath(configPath);
        String runId = generateRunId();
        String submittedAt = Instant.now().toString();
        TaskRunResponse placeholder = new TaskRunResponse(
                runId,
                TaskRunStatus.PENDING,
                emptyProgress(),
                List.of(),
                null,
                configPath,
                submittedAt,
                null,
                null);
        placeholder.setTriggerSource(triggerSource);
        taskRunRepository.insert(placeholder);
        taskRunLogRepository.info(runId, "已加入队列");
        return placeholder;
    }

    public void executeAccepted(String runId, TaskRunSubmitRequest request) {
        TaskRunResponse stored = taskRunRepository.findById(runId)
                .orElseThrow(() -> new TaskRunNotFoundException(runId));
        TaskConfig job = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        List<Map<String, Object>> writers = resolveRuntimeWriters(job, request);
        long estimatedRows = estimateTotalRows(job);

        taskRunLogRepository.info(runId, "任务已提交，配置文件: " + request.getConfigPath());
        taskRunLogRepository.info(runId, "预估生成行数: " + estimatedRows);

        boolean forceAsync = stored.getTriggerSource() == TriggerSource.SCHEDULED;
        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (forceAsync || estimatedRows > syncThreshold) {
            log.info("Executing accepted job {} async (forceAsync={}, estimatedRows={}, threshold={})",
                    runId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                taskRunLogRepository.info(runId, "定时触发，转为异步执行");
            } else {
                taskRunLogRepository.info(runId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncTaskRunExecutor.submit(runId, () -> executeAndStore(runId, job, writers, options));
            return;
        }

        log.info("Executing accepted job {} sync (estimatedRows={})", runId, estimatedRows);
        taskRunLogRepository.info(runId, "同步执行中");
        executeAndStore(runId, job, writers, options);
    }

    TaskRunSubmitResult doSubmit(TaskRunSubmitRequest request, TriggerSource triggerSource) {
        validateConfigPath(request.getConfigPath());
        String runId = generateRunId();
        String submittedAt = Instant.now().toString();
        TaskConfig job = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        long estimatedRows = estimateTotalRows(job);

        TaskRunResponse placeholder = new TaskRunResponse(
                runId,
                TaskRunStatus.PENDING,
                emptyProgress(),
                List.of(),
                null,
                request.getConfigPath(),
                submittedAt,
                null,
                null);
        placeholder.setTriggerSource(triggerSource);
        taskRunRepository.insert(placeholder);
        taskRunLogRepository.info(runId, "任务已提交，配置文件: " + request.getConfigPath());
        taskRunLogRepository.info(runId, "预估生成行数: " + estimatedRows);

        boolean forceAsync = triggerSource == TriggerSource.SCHEDULED;
        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (forceAsync || estimatedRows > syncThreshold) {
            log.info("Submitting async job {} (forceAsync={}, estimatedRows={}, threshold={})",
                    runId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                taskRunLogRepository.info(runId, "定时触发，转为异步执行");
            } else {
                taskRunLogRepository.info(runId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncTaskRunExecutor.submit(runId, () -> executeAndStore(runId, job, resolveRuntimeWriters(job, request), options));
            TaskRunResponse pending = taskRunRepository.findById(runId).orElseThrow();
            return new TaskRunSubmitResult(pending, true);
        }

        log.info("Submitting sync job {} (estimatedRows={})", runId, estimatedRows);
        taskRunLogRepository.info(runId, "同步执行中");
        TaskRunResponse response = executeAndStore(runId, job, resolveRuntimeWriters(job, request), options);
        return new TaskRunSubmitResult(response, false);
    }

    public TaskRunListResponse list(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        long total = taskRunRepository.countAll();
        List<TaskRunSummaryResponse> items = taskRunRepository.listPage(offset, safeSize).stream()
                .map(this::toSummary)
                .toList();
        return new TaskRunListResponse(items, total, safePage, safeSize);
    }

    public TaskRunResponse getById(String runId) {
        return taskRunRepository.findById(runId)
                .orElseThrow(() -> new TaskRunNotFoundException(runId));
    }

    public List<TaskRunLogEntry> getLogs(String runId) {
        if (taskRunRepository.findById(runId).isEmpty()) {
            throw new TaskRunNotFoundException(runId);
        }
        return taskRunLogRepository.getLogs(runId);
    }

    public void cancel(String runId) {
        TaskRunResponse response = taskRunRepository.findById(runId)
                .orElseThrow(() -> new TaskRunNotFoundException(runId));
        if (isTerminalStatus(response.getStatus())) {
            return;
        }
        String jobConfig = response.getConfigPath();
        cancellationRegistry.markCancelled(runId);
        boolean asyncCancelled = asyncTaskRunExecutor.cancel(runId);
        boolean interrupted = cancellationRegistry.interruptRunning(runId);
        if (asyncCancelled || interrupted) {
            if (!asyncCancelled) {
                TaskRunResponse current = taskRunRepository.findById(runId).orElseThrow();
                if (!isTerminalStatus(current.getStatus())) {
                    current.setStatus(TaskRunStatus.CANCELLED);
                    taskRunRepository.update(current);
                    taskRunLogRepository.warn(runId, "任务已被用户取消");
                }
            }
            scheduleExecutor.onRunTerminal(jobConfig);
            return;
        }
        TaskRunResponse current = taskRunRepository.findById(runId).orElseThrow();
        if (current.getStatus() == TaskRunStatus.PENDING || current.getStatus() == TaskRunStatus.RUNNING) {
            current.setStatus(TaskRunStatus.CANCELLED);
            taskRunRepository.update(current);
            taskRunLogRepository.warn(runId, "任务已被用户取消");
            scheduleExecutor.onRunTerminal(jobConfig);
            return;
        }
        throw new IllegalArgumentException("Job cannot be cancelled in status: " + response.getStatus());
    }

    private static boolean isTerminalStatus(TaskRunStatus status) {
        return status == TaskRunStatus.COMPLETED
                || status == TaskRunStatus.FAILED
                || status == TaskRunStatus.CANCELLED;
    }

    @Transactional
    public void remove(String runId) {
        TaskRunResponse response = taskRunRepository.findById(runId)
                .orElseThrow(() -> new TaskRunNotFoundException(runId));
        if (response.getStatus() == TaskRunStatus.PENDING || response.getStatus() == TaskRunStatus.RUNNING) {
            throw new IllegalArgumentException("Running job cannot be removed: " + runId);
        }
        taskRunLogRepository.remove(runId);
        taskRunRepository.delete(runId);
    }

    public PreviewResponse preview(PreviewRequest request) {
        validateConfigPath(request.getConfigPath());
        long start = System.currentTimeMillis();

        TaskConfig job = loadAndApplyOverrides(request);
        TaskConfig previewJob = preparePreviewJob(job, request.getPreview());

        CollectingWriter collectingWriter = new CollectingWriter();
        JobOrchestrator previewOrchestrator = previewOrchestratorFactory.create(collectingWriter);

        GenerationOptions options = toGenerationOptions(request.getOptions());
        JobResult jobResult = previewOrchestrator.run(
                previewJob,
                Map.of("type", CollectingWriter.TYPE),
                options);
        validatePreviewResults(previewJob, jobResult);

        PreviewResponse response = new PreviewResponse();
        response.setStatus(TaskRunStatus.COMPLETED);
        response.setDuration(formatDuration(System.currentTimeMillis() - start));
        response.setTables(buildPreviewTables(previewJob, collectingWriter.toRowMaps()));
        return response;
    }

    private void validatePreviewResults(TaskConfig previewJob, JobResult jobResult) {
        for (TableResult detail : jobResult.details()) {
            if (detail.rows() > 0) {
                continue;
            }
            throw new IllegalStateException(
                    "表 '" + detail.table() + "' 预览未生成任何行，请检查 seed 数据源连接与查询是否返回数据");
        }
    }

    private TaskRunResponse executeAndStore(
            String runId,
            TaskConfig job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        long start = System.currentTimeMillis();
        TaskRunResponse current = taskRunRepository.findById(runId).orElse(null);
        String jobConfig = current == null ? null : current.getConfigPath();
        String submittedAt = current == null ? null : current.getSubmittedAt();
        cancellationRegistry.registerRunning(runId);
        int totalTables = job.getTables().size();
        long totalRows = estimateTotalRows(job);
        Map<String, TableDetail> runningDetails = new LinkedHashMap<>();
        try {
            if (current != null) {
                current.setStatus(TaskRunStatus.RUNNING);
                current.setProgress(new TaskRunProgress(totalTables, 0, totalRows, 0, 0));
                taskRunRepository.update(current);
            }
            taskRunLogRepository.info(runId, "开始生成数据，共 " + totalTables + " 张表，目标 " + totalRows + " 行");
            logJobContext(runId, job, runtimeWriters, options);
            JobExecutionListener progressListener = createProgressListener(
                    runId, jobConfig, submittedAt, totalTables, totalRows, runningDetails);
            GenerationOptions executionOptions =
                    options.withCancellationChecker(() -> isJobCancelledInMemory(runId));
            JobResult result = jobOrchestrator.run(job, runtimeWriters, executionOptions, progressListener);
            for (TableResult tableResult : result.details()) {
                taskRunLogRepository.info(
                        runId,
                        "表 [" + tableResult.table() + "] 完成: 写入 "
                                + tableResult.rows() + " 行, 失败 " + tableResult.failedRows() + " 行, 状态 "
                                + tableResult.status());
            }
            if (isJobCancelled(runId)) {
                taskRunLogRepository.warn(runId, "任务执行完毕但已被取消，保持 CANCELLED 状态");
                return taskRunRepository.findById(runId).orElseThrow(
                        () -> new IllegalStateException("Job not found: " + runId));
            }
            TaskRunResponse response = toTaskRunResponse(
                    runId,
                    TaskRunStatus.COMPLETED,
                    result,
                    start,
                    current == null ? null : current.getConfigPath(),
                    current == null ? null : current.getSubmittedAt(),
                    null,
                    null);
            taskRunRepository.update(response);
            taskRunLogRepository.info(
                    runId,
                    "任务完成，耗时 " + response.getDuration()
                            + "，共写入 " + result.writtenRows() + " 行");
            return response;
        } catch (JobCancelledException cancelled) {
            TaskRunResponse latest = taskRunRepository.findById(runId).orElseThrow(
                    () -> new IllegalStateException("Job not found: " + runId));
            if (!isTerminalStatus(latest.getStatus())) {
                latest.setStatus(TaskRunStatus.CANCELLED);
                taskRunRepository.update(latest);
                taskRunLogRepository.warn(runId, "任务已被用户取消");
            }
            return latest;
        } catch (Exception exception) {
            if (isJobCancelled(runId)) {
                throw exception;
            }
            taskRunLogRepository.error(runId, "任务执行失败: " + exception.getClass().getSimpleName() + " - " + exception.getMessage());
            if (exception.getCause() != null && exception.getCause().getMessage() != null) {
                taskRunLogRepository.error(runId, "根本原因: " + exception.getCause().getMessage());
            }
            TaskRunResponse failed = current == null
                    ? new TaskRunResponse(runId, TaskRunStatus.FAILED, emptyProgress(), List.of(), null, null, null, exception.getMessage(), null)
                    : current;
            failed.setStatus(TaskRunStatus.FAILED);
            failed.setErrorMessage(exception.getMessage());
            failed.setDetails(List.of(new TableDetail("_error", 0, 0, exception.getMessage())));
            taskRunRepository.update(failed);
            throw exception;
        } finally {
            cancellationRegistry.unregisterRunning(runId);
            if (jobConfig != null) {
                scheduleExecutor.onRunTerminal(jobConfig);
            }
        }
    }

    private TaskConfig loadAndApplyOverrides(TaskRunSubmitRequest request) {
        TaskConfig job = configLoader.loadJob(request.getConfigPath());
        applyOverrides(job, request.getOverrides());
        return job;
    }

    /**
     * 请求体 writer/writers 作为运行时默认值；Job YAML 中的 writer/writers 优先。
     */
    private List<Map<String, Object>> resolveRuntimeWriters(TaskConfig job, TaskRunSubmitRequest request) {
        List<Map<String, Object>> runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriters());
        if (runtimeWriters.isEmpty()) {
            runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriter());
        }
        validateWriterConfigured(job, runtimeWriters);
        return runtimeWriters;
    }

    private void validateWriterConfigured(TaskConfig job, List<Map<String, Object>> runtimeWriters) {
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(job, runtimeWriters);
        for (TableTask table : job.getTables()) {
            List<Map<String, Object>> effective =
                    WriterConfigResolver.resolveTableWriters(table, defaultWriters);
            WriterConfigResolver.validateWriterMapsConfigured(table.getName(), effective);
        }
    }

    private void applyOverrides(TaskConfig job, Map<String, Object> overrides) {
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

    private TaskConfig preparePreviewJob(TaskConfig job, PreviewOptions previewOptions) {
        PreviewOptions options = previewOptions == null ? new PreviewOptions() : previewOptions;
        int limit = Math.min(Math.max(options.getLimit(), 1), PREVIEW_MAX_LIMIT);
        List<String> selectedTables = options.getTables();

        TaskConfig previewJob = new TaskConfig();
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
            TaskConfig previewJob,
            Map<String, List<Map<String, Object>>> rowMaps) {
        List<PreviewTableResponse> tables = new ArrayList<>();
        for (TableTask tableTask : previewJob.getTables()) {
            TableSchema schema = resolveSchema(tableTask);
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
            TableSchema schema,
            Map<String, List<Map<String, Object>>> rowMaps) {
        String schemaTable = schema.getTable();
        if (schemaTable != null && !schemaTable.isBlank() && rowMaps.containsKey(schemaTable)) {
            return rowMaps.get(schemaTable);
        }
        return rowMaps.getOrDefault(tableTask.getName(), List.of());
    }

    private TableSchema resolveSchema(TableTask tableTask) {
        if (tableTask.getSchemaDefinition() != null) {
            return tableTask.getSchemaDefinition();
        }
        if (tableTask.getSchema() == null || tableTask.getSchema().isBlank()) {
            throw new ConfigLoadException("Table '" + tableTask.getName() + "' has no schema defined");
        }
        return configLoader.loadSchema(tableTask.getSchema());
    }

    private JobExecutionListener createProgressListener(
            String runId,
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
                taskRunLogRepository.info(
                        runId,
                        "开始生成表 [" + tableName + "]（" + (tableIndex + 1) + "/" + totalTables + "），目标 "
                                + plannedRows + " 行");
                persistRunningProgress(
                        runId,
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
                    taskRunLogRepository.info(
                            runId,
                            "表 [" + tableName + "] 近期写入 " + recentWritten + " 行（"
                                    + batchesSincePersist + " 批），任务累计 "
                                    + jobWrittenRows + " / " + totalRows + " 行");
                    persistRunningProgress(
                            runId,
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
                taskRunLogRepository.info(
                        runId,
                        "表 [" + tableName + "] 完成: 写入 " + tableWrittenRows + " 行, 失败 "
                                + tableFailedRows + " 行");
                persistRunningProgress(
                        runId,
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
            String runId,
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
        TaskRunResponse response = new TaskRunResponse(
                runId,
                TaskRunStatus.RUNNING,
                new TaskRunProgress(totalTables, completedTables, totalRows, writtenRows, failedRows),
                new ArrayList<>(runningDetails.values()),
                null,
                jobConfig,
                submittedAt,
                null,
                null);
        taskRunRepository.update(response);
    }

    private void logJobContext(
            String runId,
            TaskConfig job,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        ConnectionRegistry effectiveRegistry = connectionRegistry.withOverlay(job.getConnections());
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(job, runtimeWriters);
        taskRunLogRepository.info(runId, "Writer 配置: " + summarizeResolvedWriters(defaultWriters, effectiveRegistry));
        taskRunLogRepository.info(
                runId,
                "生成选项: batchSize=" + options.batchSize()
                        + ", maxRetries=" + options.maxRetries()
                        + ", onConstraintFail=" + options.onConstraintFail()
                        + ", generationParallelism=" + options.generationParallelism()
                        + " (并行阈值=" + GenerationOptions.PARALLEL_ROW_THRESHOLD + " 行)");
        taskRunLogRepository.info(runId, "Job seeds 数量: " + job.getSeeds().size());
        for (SeedDefinition seed : job.getSeeds()) {
            if (seed.getReader().isEmpty()) {
                continue;
            }
            taskRunLogRepository.info(
                    runId,
                    "Seed [" + seed.getName() + "] reader: "
                            + summarizeReaderConfig(effectiveRegistry.resolveReader(seed.getReader())));
        }
        for (TableTask table : job.getTables()) {
            String schemaRef = table.getSchemaDefinition() != null
                    ? "inline"
                    : table.getSchema();
            taskRunLogRepository.info(
                    runId,
                    "表 [" + table.getName() + "] count=" + table.getCount()
                            + ", schema=" + schemaRef
                            + ", depends_on=" + table.getDependsOn()
                            + ", writer=" + summarizeResolvedWriters(
                                    WriterConfigResolver.resolveTableWriters(table, defaultWriters),
                                    effectiveRegistry));
        }
    }

    private static String summarizeResolvedWriters(
            List<Map<String, Object>> writers, ConnectionRegistry registry) {
        if (writers == null || writers.isEmpty()) {
            return "[]";
        }
        if (writers.size() == 1) {
            return summarizeWriterConfig(registry.resolveWriter(writers.getFirst()));
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < writers.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(summarizeWriterConfig(registry.resolveWriter(writers.get(index))));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String summarizeWriterConfig(WriterConfig config) {
        List<String> fields = new ArrayList<>();
        addConfigField(fields, "type", config.type());
        addConfigField(fields, "connection", config.connection());
        addConfigField(fields, "mode", config.mode());
        addConfigField(fields, "table", config.table());
        addConfigField(fields, "path", config.path());
        addConfigField(fields, "url", config.url());
        addConfigField(fields, "username", config.username());
        addConfigField(fields, "password", config.password());
        return "{" + String.join(", ", fields) + "}";
    }

    private static String summarizeReaderConfig(ReaderConfig config) {
        List<String> fields = new ArrayList<>();
        addConfigField(fields, "type", config.type());
        addConfigField(fields, "connection", config.connection());
        addConfigField(fields, "path", config.path());
        addConfigField(fields, "url", config.url());
        addConfigField(fields, "username", config.username());
        addConfigField(fields, "password", config.password());
        return "{" + String.join(", ", fields) + "}";
    }

    private static void addConfigField(List<String> fields, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        fields.add(key + '=' + value);
    }

    private TaskRunResponse toTaskRunResponse(
            String runId,
            TaskRunStatus status,
            JobResult result,
            long startMillis,
            String jobConfig,
            String submittedAt,
            String errorMessage,
            Map<String, List<Map<String, Object>>> rows) {
        List<TableDetail> details = result.details().stream()
                .map(this::toTableDetail)
                .toList();
        TaskRunProgress progress = new TaskRunProgress(
                details.size(),
                details.size(),
                result.totalRows(),
                result.writtenRows(),
                result.failedRows());
        return new TaskRunResponse(
                runId,
                status,
                progress,
                details,
                formatDuration(System.currentTimeMillis() - startMillis),
                jobConfig,
                submittedAt,
                errorMessage,
                rows);
    }

    private TaskRunSummaryResponse toSummary(TaskRunResponse response) {
        TaskRunProgress progress = response.getProgress();
        long totalRows = progress == null ? 0 : progress.getTotalRows();
        long writtenRows = progress == null ? 0 : progress.getWrittenRows();
        return new TaskRunSummaryResponse(
                response.getRunId(),
                response.getConfigPath(),
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

    private GenerationOptions toGenerationOptions(TaskRunOptions options) {
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
        return new GenerationOptions(batchSize, maxRetries, onConstraintFail, resolveGenerationParallelism(options));
    }

    private int resolveGenerationParallelism(TaskRunOptions options) {
        if (options != null
                && options.getGenerationParallelism() != null
                && options.getGenerationParallelism() > 0) {
            return options.getGenerationParallelism();
        }
        return runtimeSettings.effectiveGenerationParallelism();
    }

    private int resolveSyncThreshold(TaskRunOptions options) {
        if (options != null && options.getSyncThreshold() != null && options.getSyncThreshold() > 0) {
            return options.getSyncThreshold();
        }
        return runtimeSettings.syncThreshold();
    }

    private static long estimateTotalRows(TaskConfig job) {
        return job.getTables().stream().mapToLong(TableTask::getCount).sum();
    }

    private void validateConfigPath(String jobConfig) {
        if (jobConfig == null || jobConfig.isBlank()) {
            throw new IllegalArgumentException("configPath is required");
        }
    }

    private String generateRunId() {
        return "task-run-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static TaskRunProgress emptyProgress() {
        return new TaskRunProgress(0, 0, 0, 0, 0);
    }

    /**
     * 热路径取消探测：仅查内存标记，避免每行触发 SQLite 查询。
     */
    private boolean isJobCancelledInMemory(String runId) {
        return cancellationRegistry.isCancelled(runId) || asyncTaskRunExecutor.isCancelled(runId);
    }

    private boolean isJobCancelled(String runId) {
        if (isJobCancelledInMemory(runId)) {
            return true;
        }
        return taskRunRepository.findById(runId)
                .map(job -> job.getStatus() == TaskRunStatus.CANCELLED)
                .orElse(false);
    }
}

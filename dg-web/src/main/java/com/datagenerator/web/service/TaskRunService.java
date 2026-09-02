package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.ConfigVolumeStat;
import com.datagenerator.web.dto.DailyRunStat;
import com.datagenerator.web.dto.TaskRunIndexResponse;
import com.datagenerator.web.dto.TaskRunListFilter;
import com.datagenerator.web.dto.TaskRunListResponse;
import com.datagenerator.web.dto.TaskRunLogEntry;
import com.datagenerator.web.dto.TaskRunOptions;
import com.datagenerator.web.dto.TaskRunProgress;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatsResponse;
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
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.exception.TaskRunNotFoundException;
import com.datagenerator.web.internal.CollectingWriter;
import com.datagenerator.core.config.ConnectionRegistry;
import com.datagenerator.core.config.WriterConfigResolver;
import com.datagenerator.core.constraint.ConstraintLoader;
import com.datagenerator.core.engine.GenerationOptions;
import com.datagenerator.core.engine.TaskRunCancelledException;
import com.datagenerator.core.engine.TaskRunExecutionListener;
import com.datagenerator.core.engine.TaskRunOrchestrator;
import com.datagenerator.core.engine.TaskRunResult;
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
import com.datagenerator.web.storage.TaskRepository;
import com.datagenerator.web.storage.TaskRunLogRepository;
import com.datagenerator.web.storage.TaskRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskRunService {

    private static final Logger log = LoggerFactory.getLogger(TaskRunService.class);
    private static final int PREVIEW_MAX_LIMIT = 100;
    private static final int MAX_PAGE_SIZE = 200;
    /** 概览统计数据量排行的条目上限 */
    private static final int STATS_TOP_CONFIG_LIMIT = 10;
    /** 概览每日趋势的天数窗口 */
    private static final int STATS_DAILY_DAYS = 14;
    /** submitted_at 统一为固定 9 位小数的 ISO 格式，保证 TEXT 字典序即时间序 */
    private static final DateTimeFormatter SUBMITTED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    /** 生成固定格式的提交时间字符串 */
    private static String formatSubmittedAt(Instant instant) {
        return SUBMITTED_AT_FORMATTER.format(instant);
    }

    private final TaskRunOrchestrator taskRunOrchestrator;
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
    private final TaskRepository taskRepository;

    public TaskRunService(
            TaskRunOrchestrator taskRunOrchestrator,
            PreviewOrchestratorFactory previewOrchestratorFactory,
            YamlConfigLoader configLoader,
            ConstraintLoader constraintLoader,
            ConnectionRegistry connectionRegistry,
            TaskRunRuntimeSettings runtimeSettings,
            TaskRunRepository taskRunRepository,
            TaskRunLogRepository taskRunLogRepository,
            AsyncTaskRunExecutor asyncTaskRunExecutor,
            TaskRunCancellationRegistry cancellationRegistry,
            @Lazy TaskRunQueueExecutor scheduleExecutor,
            TaskRepository taskRepository) {
        this.taskRunOrchestrator = taskRunOrchestrator;
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
        this.taskRepository = taskRepository;
    }

    public TaskRunSubmitResult submit(TaskRunSubmitRequest request) {
        return scheduleExecutor.enqueue(request.getConfigPath(), TriggerSource.MANUAL, request);
    }

    public TaskRunResponse createQueuedRun(String configPath, TriggerSource triggerSource) {
        validateConfigPath(configPath);
        String runId = generateRunId();
        String submittedAt = formatSubmittedAt(Instant.now());
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
        TaskConfig taskConfig = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        List<Map<String, Object>> writers = resolveRuntimeWriters(taskConfig, request);
        long estimatedRows = estimateTotalRows(taskConfig);

        taskRunLogRepository.info(runId, "任务已提交，配置文件: " + request.getConfigPath());
        taskRunLogRepository.info(runId, "预估生成行数: " + estimatedRows);

        boolean forceAsync = stored.getTriggerSource() == TriggerSource.SCHEDULED;
        int syncThreshold = resolveSyncThreshold(request.getOptions());
        if (forceAsync || estimatedRows > syncThreshold) {
            log.info("Executing accepted task run {} async (forceAsync={}, estimatedRows={}, threshold={})",
                    runId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                taskRunLogRepository.info(runId, "定时触发，转为异步执行");
            } else {
                taskRunLogRepository.info(runId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncTaskRunExecutor.submit(runId, () -> executeAndStore(runId, taskConfig, writers, options));
            return;
        }

        log.info("Executing accepted task run {} sync (estimatedRows={})", runId, estimatedRows);
        taskRunLogRepository.info(runId, "同步执行中");
        executeAndStore(runId, taskConfig, writers, options);
    }

    TaskRunSubmitResult doSubmit(TaskRunSubmitRequest request, TriggerSource triggerSource) {
        validateConfigPath(request.getConfigPath());
        String runId = generateRunId();
        String submittedAt = formatSubmittedAt(Instant.now());
        TaskConfig taskConfig = loadAndApplyOverrides(request);
        GenerationOptions options = toGenerationOptions(request.getOptions());
        long estimatedRows = estimateTotalRows(taskConfig);

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
            log.info("Submitting async task run {} (forceAsync={}, estimatedRows={}, threshold={})",
                    runId, forceAsync, estimatedRows, syncThreshold);
            if (forceAsync) {
                taskRunLogRepository.info(runId, "定时触发，转为异步执行");
            } else {
                taskRunLogRepository.info(runId, "超过同步阈值 " + syncThreshold + "，转为异步执行");
            }
            asyncTaskRunExecutor.submit(runId, () -> executeAndStore(runId, taskConfig, resolveRuntimeWriters(taskConfig, request), options));
            TaskRunResponse pending = taskRunRepository.findById(runId).orElseThrow();
            return new TaskRunSubmitResult(pending, true);
        }

        log.info("Submitting sync task run {} (estimatedRows={})", runId, estimatedRows);
        taskRunLogRepository.info(runId, "同步执行中");
        TaskRunResponse response = executeAndStore(runId, taskConfig, resolveRuntimeWriters(taskConfig, request), options);
        return new TaskRunSubmitResult(response, false);
    }

    public TaskRunListResponse list(int page, int size, TaskRunListFilter filter) {
        TaskRunListFilter validated = validateFilter(filter);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = (safePage - 1) * safeSize;
        long total = taskRunRepository.countAll(validated);
        List<TaskRunSummaryResponse> items = taskRunRepository.listPage(offset, safeSize, validated).stream()
                .map(this::toSummary)
                .toList();
        return new TaskRunListResponse(items, total, safePage, safeSize);
    }

    /** 概览统计：状态计数、累计写入、Top 配置排行与近 14 天每日趋势（缺失日期补零） */
    public TaskRunStatsResponse stats() {
        Map<String, Long> byStatus = taskRunRepository.countByStatus();
        long totalRuns = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long totalWritten = taskRunRepository.sumWrittenRows();
        List<ConfigVolumeStat> topConfigs =
                taskRunRepository.topWrittenByConfigPath(STATS_TOP_CONFIG_LIMIT);

        LocalDate start = LocalDate.now(ZoneId.systemDefault()).minusDays(STATS_DAILY_DAYS - 1L);
        Map<String, DailyRunStat> byDay = taskRunRepository.dailyRunStats(start.toString()).stream()
                .collect(Collectors.toMap(DailyRunStat::date, stat -> stat));
        List<DailyRunStat> daily = new ArrayList<>();
        for (int offset = 0; offset < STATS_DAILY_DAYS; offset++) {
            String date = start.plusDays(offset).toString();
            daily.add(byDay.getOrDefault(date, new DailyRunStat(date, 0, 0)));
        }

        return new TaskRunStatsResponse(
                totalRuns,
                countOfStatus(byStatus, "RUNNING"),
                countOfStatus(byStatus, "PENDING"),
                countOfStatus(byStatus, "COMPLETED"),
                countOfStatus(byStatus, "FAILED"),
                countOfStatus(byStatus, "CANCELLED"),
                totalWritten,
                topConfigs,
                daily);
    }

    /** 按配置路径聚合的运行索引：每路径最新一次与活跃运行 */
    public TaskRunIndexResponse runIndexes() {
        List<TaskRunSummaryResponse> latestRuns = taskRunRepository.latestRunsByConfigPath().stream()
                .map(this::toSummary)
                .toList();
        List<TaskRunSummaryResponse> activeRuns = taskRunRepository.activeRunsByConfigPath().stream()
                .map(this::toSummary)
                .toList();
        return new TaskRunIndexResponse(latestRuns, activeRuns);
    }

    private static long countOfStatus(Map<String, Long> byStatus, String status) {
        return byStatus.getOrDefault(status, 0L);
    }

    /** 校验并规范化查询条件：非法状态或时间格式直接拒绝 */
    private static TaskRunListFilter validateFilter(TaskRunListFilter filter) {
        if (filter == null) {
            return new TaskRunListFilter(null, null, null, null);
        }
        String status = normalizeStatuses(filter.status());
        String from = validateInstant(filter.from(), "from");
        String to = validateInstant(filter.to(), "to");
        return new TaskRunListFilter(status, normalize(filter.configPath()), from, to);
    }

    /** 校验并规范化状态条件：支持逗号分隔多状态，空段剔除 */
    private static String normalizeStatuses(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return null;
        }
        List<String> statuses = Arrays.stream(normalized.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
        if (statuses.isEmpty()) {
            return null;
        }
        for (String single : statuses) {
            try {
                TaskRunStatus.valueOf(single);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown task run status: " + single);
            }
        }
        return String.join(",", statuses);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String validateInstant(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            Instant.parse(normalized);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid " + name + " time: " + value);
        }
        return normalized;
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
        String configPath = response.getConfigPath();
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
            scheduleExecutor.onRunTerminal(configPath);
            return;
        }
        TaskRunResponse current = taskRunRepository.findById(runId).orElseThrow();
        if (current.getStatus() == TaskRunStatus.PENDING || current.getStatus() == TaskRunStatus.RUNNING) {
            current.setStatus(TaskRunStatus.CANCELLED);
            taskRunRepository.update(current);
            taskRunLogRepository.warn(runId, "任务已被用户取消");
            scheduleExecutor.onRunTerminal(configPath);
            return;
        }
        throw new IllegalArgumentException("Task run cannot be cancelled in status: " + response.getStatus());
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
            throw new IllegalArgumentException("Running task run cannot be removed: " + runId);
        }
        taskRunLogRepository.remove(runId);
        taskRunRepository.delete(runId);
    }

    public PreviewResponse preview(PreviewRequest request) {
        validateConfigPath(request.getConfigPath());
        long start = System.currentTimeMillis();

        TaskConfig taskConfig = loadAndApplyOverrides(request);
        TaskConfig previewTaskConfig = preparePreviewTaskConfig(taskConfig, request.getPreview());

        CollectingWriter collectingWriter = new CollectingWriter();
        TaskRunOrchestrator previewOrchestrator = previewOrchestratorFactory.create(collectingWriter);

        GenerationOptions options = toGenerationOptions(request.getOptions());
        TaskRunResult taskRunResult = previewOrchestrator.run(
                previewTaskConfig,
                Map.of("type", CollectingWriter.TYPE),
                options);
        validatePreviewResults(previewTaskConfig, taskRunResult);

        PreviewResponse response = new PreviewResponse();
        response.setStatus(TaskRunStatus.COMPLETED);
        response.setDuration(formatDuration(System.currentTimeMillis() - start));
        response.setTables(buildPreviewTables(previewTaskConfig, collectingWriter.toRowMaps()));
        return response;
    }

    private void validatePreviewResults(TaskConfig previewTaskConfig, TaskRunResult taskRunResult) {
        for (TableResult detail : taskRunResult.details()) {
            if (detail.rows() > 0) {
                continue;
            }
            throw new IllegalStateException(
                    "表 '" + detail.table() + "' 预览未生成任何行，请检查 seed 数据源连接与查询是否返回数据");
        }
    }

    private TaskRunResponse executeAndStore(
            String runId,
            TaskConfig taskConfig,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        long start = System.currentTimeMillis();
        TaskRunResponse current = taskRunRepository.findById(runId).orElse(null);
        String configPath = current == null ? null : current.getConfigPath();
        String submittedAt = current == null ? null : current.getSubmittedAt();
        cancellationRegistry.registerRunning(runId);
        int totalTables = taskConfig.getTables().size();
        long totalRows = estimateTotalRows(taskConfig);
        Map<String, TableDetail> runningDetails = new LinkedHashMap<>();
        try {
            if (current != null) {
                current.setStatus(TaskRunStatus.RUNNING);
                current.setProgress(new TaskRunProgress(totalTables, 0, totalRows, 0, 0));
                taskRunRepository.update(current);
            }
            taskRunLogRepository.info(runId, "开始生成数据，共 " + totalTables + " 张表，目标 " + totalRows + " 行");
            logTaskRunContext(runId, taskConfig, runtimeWriters, options);
            TaskRunExecutionListener progressListener = createProgressListener(
                    runId, configPath, submittedAt, totalTables, totalRows, runningDetails);
            GenerationOptions executionOptions =
                    options.withCancellationChecker(() -> isTaskRunCancelledInMemory(runId));
            TaskRunResult result = taskRunOrchestrator.run(taskConfig, runtimeWriters, executionOptions, progressListener);
            for (TableResult tableResult : result.details()) {
                taskRunLogRepository.info(
                        runId,
                        "表 [" + tableResult.table() + "] 完成: 写入 "
                                + tableResult.rows() + " 行, 失败 " + tableResult.failedRows() + " 行, 状态 "
                                + tableResult.status());
            }
            if (isTaskRunCancelled(runId)) {
                taskRunLogRepository.warn(runId, "任务执行完毕但已被取消，保持 CANCELLED 状态");
                return taskRunRepository.findById(runId).orElseThrow(
                        () -> new IllegalStateException("Task run not found: " + runId));
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
        } catch (TaskRunCancelledException cancelled) {
            TaskRunResponse latest = taskRunRepository.findById(runId).orElseThrow(
                    () -> new IllegalStateException("Task run not found: " + runId));
            if (!isTerminalStatus(latest.getStatus())) {
                latest.setStatus(TaskRunStatus.CANCELLED);
                taskRunRepository.update(latest);
                taskRunLogRepository.warn(runId, "任务已被用户取消");
            }
            return latest;
        } catch (Exception exception) {
            if (isTaskRunCancelled(runId)) {
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
            if (configPath != null) {
                scheduleExecutor.onRunTerminal(configPath);
            }
        }
    }

    private TaskConfig loadAndApplyOverrides(TaskRunSubmitRequest request) {
        TaskConfig taskConfig = configLoader.loadTaskConfig(request.getConfigPath());
        applyOverrides(taskConfig, request.getOverrides());
        // 任务元数据以主表为准：执行前注入主表的 id 与显示名
        taskRepository.findByFileName(TaskConfigPaths.toFileName(request.getConfigPath()))
                .ifPresent(task -> {
                    taskConfig.setId(task.id());
                    taskConfig.setName(task.displayName());
                });
        return taskConfig;
    }

    /**
     * 请求体 writer/writers 作为运行时默认值；任务配置 YAML 中的 writer/writers 优先。
     */
    private List<Map<String, Object>> resolveRuntimeWriters(TaskConfig taskConfig, TaskRunSubmitRequest request) {
        List<Map<String, Object>> runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriters());
        if (runtimeWriters.isEmpty()) {
            runtimeWriters = WriterConfigResolver.fromRuntimeOverride(request.getWriter());
        }
        validateWriterConfigured(taskConfig, runtimeWriters);
        return runtimeWriters;
    }

    private void validateWriterConfigured(TaskConfig taskConfig, List<Map<String, Object>> runtimeWriters) {
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(taskConfig, runtimeWriters);
        for (TableTask table : taskConfig.getTables()) {
            List<Map<String, Object>> effective =
                    WriterConfigResolver.resolveTableWriters(table, defaultWriters);
            WriterConfigResolver.validateWriterMapsConfigured(table.getName(), effective);
        }
    }

    private void applyOverrides(TaskConfig taskConfig, Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            TableTask table = OverridePathResolver.resolveTable(taskConfig, entry.getKey());
            String field = OverridePathResolver.resolveField(table, entry.getKey());
            if ("count".equals(field)) {
                table.setCount(toLong(entry.getValue()));
            } else {
                throw new ConfigLoadException("Unsupported override field: " + field);
            }
        }
    }

    private TaskConfig preparePreviewTaskConfig(TaskConfig taskConfig, PreviewOptions previewOptions) {
        PreviewOptions options = previewOptions == null ? new PreviewOptions() : previewOptions;
        int limit = Math.min(Math.max(options.getLimit(), 1), PREVIEW_MAX_LIMIT);
        List<String> selectedTables = options.getTables();

        TaskConfig previewTaskConfig = new TaskConfig();
        previewTaskConfig.setName(taskConfig.getName());
        previewTaskConfig.setConstraints(taskConfig.getConstraints());
        previewTaskConfig.setInlineConstraints(new ArrayList<>(taskConfig.getInlineConstraints()));
        previewTaskConfig.setSeeds(new ArrayList<>(taskConfig.getSeeds()));
        previewTaskConfig.setWriter(Map.of());
        previewTaskConfig.setWriters(List.of());

        List<TableTask> tables = new ArrayList<>();
        for (TableTask tableTask : taskConfig.getTables()) {
            if (!selectedTables.isEmpty() && !selectedTables.contains(tableTask.getName())) {
                continue;
            }
            TableTask copy = copyTableTask(tableTask);
            copy.setCount(Math.min(copy.getCount(), limit));
            copy.setWriter(Map.of());
            copy.setWriters(List.of());
            tables.add(copy);
        }
        previewTaskConfig.setTables(tables);
        return previewTaskConfig;
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
            TaskConfig previewTaskConfig,
            Map<String, List<Map<String, Object>>> rowMaps) {
        List<PreviewTableResponse> tables = new ArrayList<>();
        for (TableTask tableTask : previewTaskConfig.getTables()) {
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

    private TaskRunExecutionListener createProgressListener(
            String runId,
            String configPath,
            String submittedAt,
            int totalTables,
            long totalRows,
            Map<String, TableDetail> runningDetails) {
        return new TaskRunExecutionListener() {
            private final long[] runWrittenRows = {0};
            private final long[] runFailedRows = {0};
            private long lastLoggedRunWrittenRows = 0;
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
                        configPath,
                        submittedAt,
                        totalTables,
                        completedTables,
                        totalRows,
                        runWrittenRows[0],
                        runFailedRows[0],
                        runningDetails);
            }

            @Override
            public void onBatchWritten(
                    String tableName,
                    int batchWritten,
                    int batchFailed,
                    long tableWrittenRows,
                    long tableFailedRows,
                    long runWrittenRows,
                    long runFailedRows) {
                this.runWrittenRows[0] = runWrittenRows;
                this.runFailedRows[0] = runFailedRows;
                runningDetails.put(
                        tableName, new TableDetail(tableName, tableWrittenRows, tableFailedRows, "running"));
                batchesSincePersist++;
                if (shouldPersistProgress()) {
                    long recentWritten = runWrittenRows - lastLoggedRunWrittenRows;
                    taskRunLogRepository.info(
                            runId,
                            "表 [" + tableName + "] 近期写入 " + recentWritten + " 行（"
                                    + batchesSincePersist + " 批），任务累计 "
                                    + runWrittenRows + " / " + totalRows + " 行");
                    persistRunningProgress(
                            runId,
                            configPath,
                            submittedAt,
                            totalTables,
                            countFinishedTables(runningDetails),
                            totalRows,
                            runWrittenRows,
                            runFailedRows,
                            runningDetails);
                    lastLoggedRunWrittenRows = runWrittenRows;
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
                    long runWrittenRows,
                    long runFailedRows) {
                this.runWrittenRows[0] = runWrittenRows;
                this.runFailedRows[0] = runFailedRows;
                String status = tableFailedRows > 0 ? "partial" : "ok";
                runningDetails.put(tableName, new TableDetail(tableName, tableWrittenRows, tableFailedRows, status));
                taskRunLogRepository.info(
                        runId,
                        "表 [" + tableName + "] 完成: 写入 " + tableWrittenRows + " 行, 失败 "
                                + tableFailedRows + " 行");
                persistRunningProgress(
                        runId,
                        configPath,
                        submittedAt,
                        totalTables,
                        completedTables,
                        totalRows,
                        runWrittenRows,
                        runFailedRows,
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
            String configPath,
            String submittedAt,
            int totalTables,
            int completedTables,
            long totalRows,
            long writtenRows,
            long failedRows,
            Map<String, TableDetail> runningDetails) {
        if (configPath == null) {
            return;
        }
        TaskRunResponse response = new TaskRunResponse(
                runId,
                TaskRunStatus.RUNNING,
                new TaskRunProgress(totalTables, completedTables, totalRows, writtenRows, failedRows),
                new ArrayList<>(runningDetails.values()),
                null,
                configPath,
                submittedAt,
                null,
                null);
        taskRunRepository.update(response);
    }

    private void logTaskRunContext(
            String runId,
            TaskConfig taskConfig,
            List<Map<String, Object>> runtimeWriters,
            GenerationOptions options) {
        ConnectionRegistry effectiveRegistry = connectionRegistry.withOverlay(taskConfig.getConnections());
        List<Map<String, Object>> defaultWriters =
                WriterConfigResolver.resolveDefaultWriters(taskConfig, runtimeWriters);
        taskRunLogRepository.info(runId, "Writer 配置: " + summarizeResolvedWriters(defaultWriters, effectiveRegistry));
        taskRunLogRepository.info(
                runId,
                "生成选项: batchSize=" + options.batchSize()
                        + ", maxRetries=" + options.maxRetries()
                        + ", onConstraintFail=" + options.onConstraintFail()
                        + ", generationParallelism=" + options.generationParallelism()
                        + " (并行阈值=" + GenerationOptions.PARALLEL_ROW_THRESHOLD + " 行)");
        taskRunLogRepository.info(runId, "Task seeds 数量: " + taskConfig.getSeeds().size());
        for (SeedDefinition seed : taskConfig.getSeeds()) {
            if (seed.getReader().isEmpty()) {
                continue;
            }
            taskRunLogRepository.info(
                    runId,
                    "Seed [" + seed.getName() + "] reader: "
                            + summarizeReaderConfig(effectiveRegistry.resolveReader(seed.getReader())));
        }
        for (TableTask table : taskConfig.getTables()) {
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
            TaskRunResult result,
            long startMillis,
            String configPath,
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
                configPath,
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

    private static long estimateTotalRows(TaskConfig taskConfig) {
        return taskConfig.getTables().stream().mapToLong(TableTask::getCount).sum();
    }

    private void validateConfigPath(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("configPath is required");
        }
        String fileName = TaskConfigPaths.toFileName(configPath);
        if (!taskRepository.existsByFileName(fileName)) {
            // 历史运行记录引用已删除任务时，重跑/预览返回 404
            throw new TaskConfigNotFoundException("Task config not found: " + fileName);
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
    private boolean isTaskRunCancelledInMemory(String runId) {
        return cancellationRegistry.isCancelled(runId) || asyncTaskRunExecutor.isCancelled(runId);
    }

    private boolean isTaskRunCancelled(String runId) {
        if (isTaskRunCancelledInMemory(runId)) {
            return true;
        }
        return taskRunRepository.findById(runId)
                .map(taskRun -> taskRun.getStatus() == TaskRunStatus.CANCELLED)
                .orElse(false);
    }
}

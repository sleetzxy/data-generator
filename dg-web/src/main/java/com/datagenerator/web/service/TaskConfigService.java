package com.datagenerator.web.service;

import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.YamlConfigLoader;
import com.datagenerator.web.dto.TaskConfigListResponse;
import com.datagenerator.web.dto.TaskConfigRequest;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskConfigValidationResponse;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
import com.datagenerator.web.storage.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TaskConfigService {

    private static final Logger log = LoggerFactory.getLogger(TaskConfigService.class);
    private static final int MAX_LIST_SIZE = 200;

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final TaskRepository taskRepository;
    private final TaskScheduleService scheduleService;
    private final TaskScheduleManager scheduleManager;
    private final TaskRunQueueExecutor scheduleExecutor;

    public TaskConfigService(
            ConfigPathResolver pathResolver,
            TaskRepository taskRepository,
            TaskScheduleService scheduleService,
            @Lazy TaskScheduleManager scheduleManager,
            TaskRunQueueExecutor scheduleExecutor) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.taskRepository = taskRepository;
        this.scheduleService = scheduleService;
        this.scheduleManager = scheduleManager;
        this.scheduleExecutor = scheduleExecutor;
    }

    public TaskConfigValidationResponse validateYaml(String yaml) {
        try {
            configLoader.loadTaskConfigFromContent(stripMetaFields(yaml));
            return TaskConfigValidationResponse.ok();
        } catch (ConfigLoadException | IllegalArgumentException exception) {
            // 非法输入（含非 mapping YAML）统一返回校验失败，保持 200 契约
            return TaskConfigValidationResponse.fail(List.of(exception.getMessage()));
        }
    }

    public TaskConfigListResponse list() {
        return list(null, null, null);
    }

    public TaskConfigListResponse list(String nameKeyword) {
        return list(nameKeyword, null, null);
    }

    /**
     * 分页查询任务列表；page/size 任一为空时返回全量
     * （兼容无分页调用方）
     */
    public TaskConfigListResponse list(String nameKeyword, Integer page, Integer size) {
        if (page == null || size == null) {
            List<TaskConfigResponse> all = taskRepository
                    .listPage(0, MAX_LIST_SIZE, nameKeyword)
                    .stream()
                    .map(this::toResponse)
                    .toList();
            // total 取全量计数，避免列表超过 MAX_LIST_SIZE 时被截断
            long total = taskRepository.count(nameKeyword);
            return new TaskConfigListResponse(all, total, 1, all.size());
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_LIST_SIZE);
        long total = taskRepository.count(nameKeyword);
        List<TaskConfigResponse> items = taskRepository
                .listPage((safePage - 1) * safeSize, safeSize, nameKeyword)
                .stream()
                .map(this::toResponse)
                .toList();
        return new TaskConfigListResponse(items, total, safePage, safeSize);
    }

    public TaskConfigResponse get(String name) {
        TaskRepository.TaskRecord task = taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException(
                        "Task config not found: " + name));
        String configPath = TaskConfigPaths.toConfigPath(name);
        String content = readContent(configPath);
        // 仅校验文件内容可正常加载；显示名与 id 均以主表为准
        configLoader.loadTaskConfigFromContent(content);
        return toResponse(task, content);
    }

    public TaskConfigResponse create(TaskConfigRequest request) {
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = stripMetaFields(requireContent(request.getContent()));
        validateContent(content);
        String taskId = generateUniqueTaskId();
        String fileName = resolveCreateFileName(request, taskId);
        String configPath = TaskConfigPaths.toConfigPath(fileName);
        if (taskRepository.existsByFileName(fileName)) {
            throw new IllegalArgumentException("Task config already exists: " + fileName);
        }
        String now = Instant.now().toString();
        writeContent(configPath, content);
        try {
            taskRepository.insert(new TaskRepository.TaskRecord(
                    taskId, fileName, displayName, false, null, now, null));
            applySchedule(configPath, normalizedSchedule);
            return toResponse(taskRepository.findByFileName(fileName).orElseThrow(),
                    content);
        } catch (RuntimeException exception) {
            rollbackDefinition(configPath);
            // 同步删除主表行，避免“表有行、文件缺失”的僵尸状态
            taskRepository.deleteByFileName(fileName);
            throw exception;
        }
    }

    public TaskConfigResponse update(String name, TaskConfigRequest request) {
        taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException(
                        "Task config not found: " + name));
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = stripMetaFields(requireContent(request.getContent()));
        validateContent(content);
        String configPath = TaskConfigPaths.toConfigPath(name);
        writeContent(configPath, content);
        taskRepository.update(name, displayName, Instant.now().toString());
        applySchedule(configPath, normalizedSchedule);
        return toResponse(taskRepository.findByFileName(name).orElseThrow(), content);
    }

    /** 以表行为准：表行存在即可删除，文件缺失不阻塞 */
    public void delete(String name) {
        taskRepository.findByFileName(name)
                .orElseThrow(() -> new TaskConfigNotFoundException(
                        "Task config not found: " + name));
        String configPath = TaskConfigPaths.toConfigPath(name);
        scheduleManager.cancel(configPath);
        scheduleExecutor.clearQueue(configPath);
        taskRepository.deleteByFileName(name);
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile != null) {
            try {
                Files.deleteIfExists(overlayFile);
            } catch (IOException exception) {
                log.warn("删除任务配置文件失败 {}: {}",
                        overlayFile, exception.getMessage());
            }
        }
    }

    private TaskConfigResponse toResponse(TaskRepository.TaskRecord task) {
        return toResponse(task, null);
    }

    private TaskConfigResponse toResponse(TaskRepository.TaskRecord task, String content) {
        TaskConfigResponse response = new TaskConfigResponse(
                task.fileName(),
                TaskConfigPaths.toConfigPath(task.fileName()),
                task.id(),
                task.displayName(),
                content);
        String configPath = TaskConfigPaths.toConfigPath(task.fileName());
        response.setSchedule(scheduleService.resolveSchedule(configPath));
        response.setCreatedAt(task.createdAt());
        return response;
    }

    private void validateContent(String content) {
        Map<?, ?> root = parseRootMapping(content);
        if (root.containsKey("schedule")) {
            throw new IllegalArgumentException(
                    "任务 YAML 不允许包含 schedule 块，"
                            + "调度请通过 schedule 接口或请求字段配置");
        }
        configLoader.loadTaskConfigFromContent(content);
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Task config content is required");
        }
        return content;
    }

    /** 剥离 YAML 中的 id/name 元数据字段（元数据以主表为准） */
    private String stripMetaFields(String content) {
        Map<String, Object> root = toMutableRoot(parseRootMapping(content));
        root.remove("id");
        root.remove("name");
        return new Yaml().dump(root);
    }

    private Map<?, ?> parseRootMapping(String content) {
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Task config YAML must be a mapping");
        }
        return root;
    }

    private Map<String, Object> toMutableRoot(Map<?, ?> root) {
        Map<String, Object> mutable = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            mutable.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mutable;
    }

    private TaskScheduleRequest normalizeScheduleIfPresent(TaskScheduleRequest schedule) {
        if (schedule == null) {
            return null;
        }
        return scheduleService.validateAndNormalize(schedule);
    }

    private void applySchedule(String configPath, TaskScheduleRequest normalizedSchedule) {
        if (normalizedSchedule == null) {
            return;
        }
        scheduleService.persistSchedule(configPath, normalizedSchedule);
        scheduleManager.reschedule(configPath);
    }

    private void rollbackDefinition(String configPath) {
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(overlayFile);
        } catch (IOException exception) {
            log.warn("回滚任务配置文件失败 {}: {}", overlayFile, exception.getMessage());
        }
    }

    private String resolveCreateFileName(TaskConfigRequest request, String taskId) {
        if (request.getFileName() != null && !request.getFileName().isBlank()) {
            String explicitName = request.getFileName().trim();
            validateAsciiFileName(explicitName);
            return explicitName;
        }
        return taskId;
    }

    private void validateAsciiFileName(String fileName) {
        if (!fileName.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Task config file name must use ASCII letters, digits, underscore, hyphen: "
                            + fileName);
        }
    }

    private String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Task config display name is required");
        }
        return displayName.trim();
    }

    private String generateUniqueTaskId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String id = "task" + suffix;
            if (taskRepository.findById(id).isEmpty() && !taskRepository.existsByFileName(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique task config id");
    }

    private String readContent(String configPath) {
        Path overlayFile = requireOverlayFile(configPath);
        if (!Files.isRegularFile(overlayFile)) {
            throw new TaskConfigNotFoundException("Task config file missing: " + configPath);
        }
        try {
            return Files.readString(overlayFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to read task config: " + configPath, exception);
        }
    }

    private void writeContent(String configPath, String content) {
        Path overlayFile = requireOverlayFile(configPath);
        try {
            Files.createDirectories(overlayFile.getParent());
            Files.writeString(overlayFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to write task config: " + configPath, exception);
        }
    }

    private Path requireOverlayFile(String configPath) {
        Path overlayRoot = pathResolver.writableOverlay();
        if (overlayRoot == null) {
            throw new IllegalStateException("Writable config directory is not configured");
        }
        return overlayRoot.resolve(configPath).normalize();
    }
}

package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskConfigRequest;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskConfigValidationResponse;
import com.datagenerator.web.storage.TaskScheduleRepository;
import com.datagenerator.core.model.ConfigLoadException;
import com.datagenerator.core.model.ConfigPathResolver;
import com.datagenerator.core.model.TaskConfig;
import com.datagenerator.core.model.YamlConfigLoader;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TaskConfigService {

    private static final String JOBS_DIR = "jobs";

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final TaskScheduleService scheduleService;
    private final TaskScheduleManager scheduleManager;
    private final TaskRunQueueExecutor scheduleExecutor;
    private final TaskScheduleRepository scheduleRepository;
    private final Yaml yaml = new Yaml();

    public TaskConfigService(
            ConfigPathResolver pathResolver,
            TaskScheduleService scheduleService,
            @Lazy TaskScheduleManager scheduleManager,
            TaskRunQueueExecutor scheduleExecutor,
            TaskScheduleRepository scheduleRepository) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.scheduleService = scheduleService;
        this.scheduleManager = scheduleManager;
        this.scheduleExecutor = scheduleExecutor;
        this.scheduleRepository = scheduleRepository;
    }

    public TaskConfigValidationResponse validateYaml(String yaml) {
        try {
            configLoader.loadTaskConfigFromContent(yaml);
            return TaskConfigValidationResponse.ok();
        } catch (ConfigLoadException exception) {
            return TaskConfigValidationResponse.fail(List.of(exception.getMessage()));
        }
    }

    public List<TaskConfigResponse> list() {
        return list(null);
    }

    public List<TaskConfigResponse> list(String nameKeyword) {
        List<TaskConfigResponse> results = new ArrayList<>();
        for (String relativePath : listIncludedJobRelativePaths()) {
            String fileName = toDefinitionName(relativePath);
            String configPath = toConfigPath(relativePath);
            TaskConfig taskConfig = configLoader.loadTaskConfig(configPath);
            results.add(toResponse(fileName, configPath, taskConfig, null, isBuiltin(configPath)));
        }
        results.sort(this::compareForList);
        if (nameKeyword == null || nameKeyword.isBlank()) {
            return results;
        }
        String keyword = nameKeyword.trim().toLowerCase();
        return results.stream()
                .filter(item -> matchesNameKeyword(item, keyword))
                .toList();
    }

    private boolean matchesNameKeyword(TaskConfigResponse item, String keyword) {
        String displayName = item.getName();
        return displayName != null && displayName.toLowerCase().contains(keyword);
    }

    private int compareForList(TaskConfigResponse left, TaskConfigResponse right) {
        if (left.isBuiltin() != right.isBuiltin()) {
            return left.isBuiltin() ? -1 : 1;
        }
        if (left.isBuiltin()) {
            return left.getFileName().compareToIgnoreCase(right.getFileName());
        }
        Instant leftCreated = parseCreatedAt(left.getCreatedAt());
        Instant rightCreated = parseCreatedAt(right.getCreatedAt());
        int byTime = rightCreated.compareTo(leftCreated);
        if (byTime != 0) {
            return byTime;
        }
        return right.getFileName().compareToIgnoreCase(left.getFileName());
    }

    private Instant parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return Instant.EPOCH;
        }
        return Instant.parse(createdAt);
    }

    public TaskConfigResponse get(String name) {
        String configPath = toConfigPath(name);
        String content = stripNameFromContent(readContent(configPath));
        TaskConfig taskConfig = configLoader.loadTaskConfig(configPath);
        return toResponse(name, configPath, taskConfig, content, isBuiltin(configPath));
    }

    public TaskConfigResponse create(TaskConfigRequest request) {
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String contentWithId = assignGeneratedId(request.getContent());
        String generatedId = requireId(parseRootMapping(contentWithId));
        String fileName = resolveCreateFileName(request, generatedId);
        String configPath = toConfigPath(fileName);
        if (exists(configPath)) {
            throw new IllegalArgumentException("Task config already exists: " + fileName);
        }
        String content = injectDisplayName(contentWithId, displayName);
        validateContent(content, null);
        writeContent(configPath, content);
        scheduleRepository.ensureCreatedAt(configPath, Instant.now().toString());
        try {
            applySchedule(configPath, normalizedSchedule);
            TaskConfig taskConfig = configLoader.loadTaskConfig(configPath);
            return toResponse(fileName, configPath, taskConfig, stripNameFromContent(content), false);
        } catch (RuntimeException exception) {
            rollbackOverlayDefinition(configPath);
            throw exception;
        }
    }

    public TaskConfigResponse update(String name, TaskConfigRequest request) {
        String configPath = toConfigPath(name);
        if (!exists(configPath)) {
            throw new ConfigLoadException("Task config not found: " + name);
        }
        if (isBuiltin(configPath)) {
            throw new IllegalArgumentException("Built-in task config cannot be modified: " + name);
        }
        String displayName = requireDisplayName(request.getDisplayName());
        TaskScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = injectDisplayName(request.getContent(), displayName);
        validateContent(content, configPath);
        writeContent(configPath, content);
        applySchedule(configPath, normalizedSchedule);
        TaskConfig taskConfig = configLoader.loadTaskConfig(configPath);
        return toResponse(name, configPath, taskConfig, stripNameFromContent(content), false);
    }

    public void delete(String name) {
        String configPath = toConfigPath(name);
        if (isBuiltin(configPath)) {
            throw new IllegalArgumentException("Built-in task config cannot be deleted: " + name);
        }
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            throw new IllegalArgumentException("Task config not found: " + name);
        }
        scheduleManager.cancel(configPath);
        scheduleExecutor.clearQueue(configPath);
        scheduleRepository.deleteByConfigPath(configPath);
        try {
            Files.delete(overlayFile);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to delete task config: " + name, exception);
        }
    }

    private TaskConfigResponse toResponse(
            String fileName,
            String configPath,
            TaskConfig taskConfig,
            String content,
            boolean builtin) {
        String id = taskConfig.getId();
        if (id == null || id.isBlank()) {
            throw new ConfigLoadException("Task config missing id: " + configPath);
        }
        String displayName = taskConfig.getName();
        if (displayName == null || displayName.isBlank()) {
            displayName = fileName;
        }
        TaskConfigResponse response =
                new TaskConfigResponse(fileName, configPath, id, displayName, content, builtin, builtin);
        response.setSchedule(scheduleService.resolveSchedule(configPath, builtin));
        response.setCreatedAt(resolveCreatedAt(configPath, builtin));
        return response;
    }

    private String resolveCreatedAt(String configPath, boolean builtin) {
        if (builtin) {
            return null;
        }
        Optional<String> stored = scheduleRepository.findCreatedAt(configPath);
        if (stored.isPresent()) {
            return stored.get();
        }
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            return null;
        }
        Instant created = readFileCreationTime(overlayFile);
        if (created == null) {
            return null;
        }
        String createdAt = created.toString();
        scheduleRepository.ensureCreatedAt(configPath, createdAt);
        return createdAt;
    }

    private Instant readFileCreationTime(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            Instant created = attributes.creationTime().toInstant();
            if (created.equals(Instant.EPOCH)) {
                return null;
            }
            return created;
        } catch (IOException exception) {
            return null;
        }
    }

    private void validateContent(String content, String excludeConfigPath) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Task config content is required");
        }
        Map<?, ?> root = parseRootMapping(content);
        boolean custom = excludeConfigPath == null || !isBuiltin(excludeConfigPath);
        if (custom && root.containsKey("schedule")) {
            throw new IllegalArgumentException("Custom task config YAML must not contain schedule block");
        }
        String id = requireId(root);
        validateIdFormat(id);
        validateIdUnique(id, excludeConfigPath);
    }

    private Map<?, ?> parseRootMapping(String content) {
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Task config YAML must be a mapping");
        }
        return root;
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

    private void rollbackOverlayDefinition(String configPath) {
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            return;
        }
        try {
            Files.delete(overlayFile);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to rollback task config: " + configPath, exception);
        }
        scheduleRepository.deleteByConfigPath(configPath);
        scheduleManager.cancel(configPath);
    }

    private String assignGeneratedId(String content) {
        Map<String, Object> root = toMutableRoot(parseRootMapping(content));
        root.remove("name");
        root.put("id", generateUniqueJobId());
        return yaml.dump(root);
    }

    private String injectDisplayName(String content, String displayName) {
        Map<String, Object> root = toMutableRoot(parseRootMapping(content));
        root.put("name", displayName.trim());
        return yaml.dump(root);
    }

    private String stripNameFromContent(String content) {
        Map<String, Object> root = toMutableRoot(parseRootMapping(content));
        root.remove("name");
        return yaml.dump(root);
    }

    private String requireDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Task config display name is required");
        }
        return displayName.trim();
    }

    private String resolveCreateFileName(TaskConfigRequest request, String generatedId) {
        if (request.getName() != null && !request.getName().isBlank()) {
            String explicitName = request.getName().trim();
            validateName(explicitName);
            validateAsciiFileName(explicitName);
            return explicitName;
        }
        return generatedId;
    }

    private void validateAsciiFileName(String name) {
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Task config file name must use ASCII letters, digits, underscore, hyphen: " + name);
        }
    }

    private Map<String, Object> toMutableRoot(Map<?, ?> root) {
        Map<String, Object> mutable = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            mutable.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return mutable;
    }

    private String generateUniqueJobId() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String id = "task" + suffix;
            if (validateIdFormatQuiet(id) && !idExists(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique task config id");
    }

    private boolean validateIdFormatQuiet(String id) {
        return id.matches("[a-zA-Z][a-zA-Z0-9_-]*");
    }

    private boolean idExists(String id) {
        for (String relativePath : listIncludedJobRelativePaths()) {
            TaskConfig existing = configLoader.loadTaskConfig(toConfigPath(relativePath));
            if (id.equals(existing.getId())) {
                return true;
            }
        }
        return false;
    }

    private String requireId(Map<?, ?> root) {
        Object idValue = root.get("id");
        if (idValue == null || String.valueOf(idValue).isBlank()) {
            throw new IllegalArgumentException("Task config YAML id field is required");
        }
        return String.valueOf(idValue).trim();
    }

    private void validateIdFormat(String id) {
        if (!id.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Invalid task config id: " + id + " (use letters, digits, underscore, hyphen; start with letter)");
        }
    }

    private void validateIdUnique(String id, String excludeConfigPath) {
        for (String relativePath : listIncludedJobRelativePaths()) {
            String configPath = toConfigPath(relativePath);
            if (configPath.equals(excludeConfigPath)) {
                continue;
            }
            TaskConfig existing = configLoader.loadTaskConfig(configPath);
            if (id.equals(existing.getId())) {
                throw new IllegalArgumentException("Task config id already exists: " + id);
            }
        }
    }

    private boolean exists(String configPath) {
        if (pathResolver.existsOnOverlay(configPath)) {
            return true;
        }
        try (InputStream inputStream = pathResolver.open(configPath)) {
            return inputStream.read() >= 0;
        } catch (ConfigLoadException exception) {
            return false;
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to check task config: " + configPath, exception);
        }
    }

    private boolean isBuiltin(String configPath) {
        return pathResolver.existsOnClasspath(configPath);
    }

    private List<String> listIncludedJobRelativePaths() {
        List<String> included = new ArrayList<>();
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            if (isListedJobPath(relativePath, toConfigPath(relativePath))) {
                included.add(relativePath);
            }
        }
        return included;
    }

    /** 内置任务仅扫描 jobs 目录直属 YAML，忽略子目录。 */
    private boolean isListedJobPath(String relativePath, String configPath) {
        return !relativePath.contains("/") || !isBuiltin(configPath);
    }

    private String readContent(String configPath) {
        try (InputStream inputStream = pathResolver.open(configPath)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Task config name is required");
        }
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid task config name: " + name);
        }
    }

    private String toConfigPath(String name) {
        String normalized = name.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
            if (normalized.startsWith(JOBS_DIR + "/")) {
            normalized = normalized.substring(JOBS_DIR.length() + 1);
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return JOBS_DIR + "/" + normalized;
        }
        return JOBS_DIR + "/" + normalized + ".yaml";
    }

    private String toDefinitionName(String relativePath) {
        return toBasename(relativePath);
    }

    private String toBasename(String relativePath) {
        String filename = relativePath;
        int slash = relativePath.lastIndexOf('/');
        if (slash >= 0) {
            filename = relativePath.substring(slash + 1);
        }
        return filename.replaceFirst("\\.ya?ml$", "");
    }
}

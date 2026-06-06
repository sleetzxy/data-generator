package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobDefinitionRequest;
import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.dto.JobScheduleRequest;
import com.datagenerator.web.storage.JobScheduleRepository;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
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
import java.util.UUID;

@Service
public class JobDefinitionService {

    private static final String JOBS_DIR = "jobs";

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final JobScheduleService scheduleService;
    private final JobScheduleManager scheduleManager;
    private final JobScheduleExecutor scheduleExecutor;
    private final JobScheduleRepository scheduleRepository;
    private final Yaml yaml = new Yaml();

    public JobDefinitionService(
            ConfigPathResolver pathResolver,
            JobScheduleService scheduleService,
            @Lazy JobScheduleManager scheduleManager,
            JobScheduleExecutor scheduleExecutor,
            JobScheduleRepository scheduleRepository) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
        this.scheduleService = scheduleService;
        this.scheduleManager = scheduleManager;
        this.scheduleExecutor = scheduleExecutor;
        this.scheduleRepository = scheduleRepository;
    }

    public List<JobDefinitionResponse> list() {
        List<JobDefinitionResponse> results = new ArrayList<>();
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            String fileName = toDefinitionName(relativePath);
            String configPath = toConfigPath(relativePath);
            JobDefinition job = configLoader.loadJob(configPath);
            results.add(toResponse(fileName, configPath, job, null, isBuiltin(configPath)));
        }
        results.sort(this::compareForList);
        return results;
    }

    private int compareForList(JobDefinitionResponse left, JobDefinitionResponse right) {
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

    public JobDefinitionResponse get(String name) {
        String configPath = toConfigPath(name);
        String content = stripNameFromContent(readContent(configPath));
        JobDefinition job = configLoader.loadJob(configPath);
        return toResponse(name, configPath, job, content, isBuiltin(configPath));
    }

    public JobDefinitionResponse create(JobDefinitionRequest request) {
        String displayName = requireDisplayName(request.getDisplayName());
        JobScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String contentWithId = assignGeneratedId(request.getContent());
        String generatedId = requireId(parseRootMapping(contentWithId));
        String fileName = resolveCreateFileName(request, generatedId);
        String configPath = toConfigPath(fileName);
        if (exists(configPath)) {
            throw new IllegalArgumentException("Job definition already exists: " + fileName);
        }
        String content = injectDisplayName(contentWithId, displayName);
        validateContent(content, null);
        writeContent(configPath, content);
        try {
            applySchedule(configPath, normalizedSchedule);
            JobDefinition job = configLoader.loadJob(configPath);
            return toResponse(fileName, configPath, job, stripNameFromContent(content), false);
        } catch (RuntimeException exception) {
            rollbackOverlayDefinition(configPath);
            throw exception;
        }
    }

    public JobDefinitionResponse update(String name, JobDefinitionRequest request) {
        String configPath = toConfigPath(name);
        if (!exists(configPath)) {
            throw new ConfigLoadException("Job definition not found: " + name);
        }
        if (isBuiltin(configPath)) {
            throw new IllegalArgumentException("Built-in job definition cannot be modified: " + name);
        }
        String displayName = requireDisplayName(request.getDisplayName());
        JobScheduleRequest normalizedSchedule = normalizeScheduleIfPresent(request.getSchedule());
        String content = injectDisplayName(request.getContent(), displayName);
        validateContent(content, configPath);
        writeContent(configPath, content);
        applySchedule(configPath, normalizedSchedule);
        JobDefinition job = configLoader.loadJob(configPath);
        return toResponse(name, configPath, job, stripNameFromContent(content), false);
    }

    public void delete(String name) {
        String configPath = toConfigPath(name);
        if (isBuiltin(configPath)) {
            throw new IllegalArgumentException("Built-in job definition cannot be deleted: " + name);
        }
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            throw new IllegalArgumentException("Job definition not found: " + name);
        }
        scheduleManager.cancel(configPath);
        scheduleExecutor.clearQueue(configPath);
        scheduleRepository.deleteByConfigPath(configPath);
        try {
            Files.delete(overlayFile);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to delete job definition: " + name, exception);
        }
    }

    private JobDefinitionResponse toResponse(
            String fileName,
            String configPath,
            JobDefinition job,
            String content,
            boolean builtin) {
        String id = job.getId();
        if (id == null || id.isBlank()) {
            throw new ConfigLoadException("Job definition missing id: " + configPath);
        }
        String displayName = job.getName();
        if (displayName == null || displayName.isBlank()) {
            displayName = fileName;
        }
        JobDefinitionResponse response =
                new JobDefinitionResponse(fileName, configPath, id, displayName, content, builtin, builtin);
        response.setSchedule(scheduleService.resolveSchedule(configPath, builtin));
        response.setCreatedAt(resolveCreatedAt(configPath, builtin));
        return response;
    }

    private String resolveCreatedAt(String configPath, boolean builtin) {
        if (builtin) {
            return null;
        }
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            return null;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(overlayFile, BasicFileAttributes.class);
            Instant created = attributes.creationTime().toInstant();
            if (created.equals(Instant.EPOCH)) {
                created = attributes.lastModifiedTime().toInstant();
            }
            return created.toString();
        } catch (IOException exception) {
            return null;
        }
    }

    private void validateContent(String content, String excludeConfigPath) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Job content is required");
        }
        Map<?, ?> root = parseRootMapping(content);
        boolean custom = excludeConfigPath == null || !isBuiltin(excludeConfigPath);
        if (custom && root.containsKey("schedule")) {
            throw new IllegalArgumentException("Custom job YAML must not contain schedule block");
        }
        String id = requireId(root);
        validateIdFormat(id);
        validateIdUnique(id, excludeConfigPath);
    }

    private Map<?, ?> parseRootMapping(String content) {
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Job YAML must be a mapping");
        }
        return root;
    }

    private JobScheduleRequest normalizeScheduleIfPresent(JobScheduleRequest schedule) {
        if (schedule == null) {
            return null;
        }
        return scheduleService.validateAndNormalize(schedule);
    }

    private void applySchedule(String configPath, JobScheduleRequest normalizedSchedule) {
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
            throw new ConfigLoadException("Failed to rollback job definition: " + configPath, exception);
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
            throw new IllegalArgumentException("Job display name is required");
        }
        return displayName.trim();
    }

    private String resolveCreateFileName(JobDefinitionRequest request, String generatedId) {
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
                    "Job file name must use ASCII letters, digits, underscore, hyphen: " + name);
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
            String id = "job" + suffix;
            if (validateIdFormatQuiet(id) && !idExists(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Failed to generate unique job id");
    }

    private boolean validateIdFormatQuiet(String id) {
        return id.matches("[a-zA-Z][a-zA-Z0-9_-]*");
    }

    private boolean idExists(String id) {
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            JobDefinition existing = configLoader.loadJob(toConfigPath(relativePath));
            if (id.equals(existing.getId())) {
                return true;
            }
        }
        return false;
    }

    private String requireId(Map<?, ?> root) {
        Object idValue = root.get("id");
        if (idValue == null || String.valueOf(idValue).isBlank()) {
            throw new IllegalArgumentException("Job YAML id field is required");
        }
        return String.valueOf(idValue).trim();
    }

    private void validateIdFormat(String id) {
        if (!id.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "Invalid job id: " + id + " (use letters, digits, underscore, hyphen; start with letter)");
        }
    }

    private void validateIdUnique(String id, String excludeConfigPath) {
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            String configPath = toConfigPath(relativePath);
            if (configPath.equals(excludeConfigPath)) {
                continue;
            }
            JobDefinition existing = configLoader.loadJob(configPath);
            if (id.equals(existing.getId())) {
                throw new IllegalArgumentException("Job id already exists: " + id);
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
            throw new ConfigLoadException("Failed to check job definition: " + configPath, exception);
        }
    }

    private boolean isBuiltin(String configPath) {
        return pathResolver.existsOnClasspath(configPath);
    }

    private String readContent(String configPath) {
        try (InputStream inputStream = pathResolver.open(configPath)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to read job definition: " + configPath, exception);
        }
    }

    private void writeContent(String configPath, String content) {
        Path overlayFile = requireOverlayFile(configPath);
        try {
            Files.createDirectories(overlayFile.getParent());
            Files.writeString(overlayFile, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to write job definition: " + configPath, exception);
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
            throw new IllegalArgumentException("Job name is required");
        }
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid job name: " + name);
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

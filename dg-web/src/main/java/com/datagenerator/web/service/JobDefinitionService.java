package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobDefinitionRequest;
import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.ConfigPathResolver;
import com.datagenerator.core.schema.JobDefinition;
import com.datagenerator.core.schema.YamlConfigLoader;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class JobDefinitionService {

    private static final String JOBS_DIR = "jobs";

    private final ConfigPathResolver pathResolver;
    private final YamlConfigLoader configLoader;
    private final Yaml yaml = new Yaml();

    public JobDefinitionService(ConfigPathResolver pathResolver) {
        this.pathResolver = pathResolver;
        this.configLoader = new YamlConfigLoader(pathResolver);
    }

    public List<JobDefinitionResponse> list() {
        List<JobDefinitionResponse> results = new ArrayList<>();
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            String fileName = toDefinitionName(relativePath);
            String configPath = toConfigPath(relativePath);
            JobDefinition job = configLoader.loadJob(configPath);
            results.add(toResponse(fileName, configPath, job, null, isBuiltin(configPath)));
        }
        return results;
    }

    public JobDefinitionResponse get(String name) {
        String configPath = toConfigPath(name);
        String content = readContent(configPath);
        JobDefinition job = configLoader.loadJob(configPath);
        return toResponse(name, configPath, job, content, isBuiltin(configPath));
    }

    public JobDefinitionResponse create(JobDefinitionRequest request) {
        validateName(request.getName());
        validateContent(request.getContent(), null);
        String configPath = toConfigPath(request.getName());
        if (exists(configPath)) {
            throw new IllegalArgumentException("Job definition already exists: " + request.getName());
        }
        writeContent(configPath, request.getContent());
        JobDefinition job = configLoader.loadJob(configPath);
        return toResponse(request.getName(), configPath, job, request.getContent(), false);
    }

    public JobDefinitionResponse update(String name, JobDefinitionRequest request) {
        String configPath = toConfigPath(name);
        if (!exists(configPath)) {
            throw new ConfigLoadException("Job definition not found: " + name);
        }
        if (isBuiltin(configPath)) {
            throw new IllegalArgumentException("Built-in job definition cannot be modified: " + name);
        }
        validateContent(request.getContent(), configPath);
        writeContent(configPath, request.getContent());
        JobDefinition job = configLoader.loadJob(configPath);
        return toResponse(name, configPath, job, request.getContent(), false);
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
        return new JobDefinitionResponse(fileName, configPath, id, displayName, content, builtin, builtin);
    }

    private void validateContent(String content, String excludeConfigPath) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Job content is required");
        }
        String id = extractRequiredId(content);
        validateIdFormat(id);
        validateIdUnique(id, excludeConfigPath);
    }

    private String extractRequiredId(String content) {
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Job YAML must be a mapping");
        }
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

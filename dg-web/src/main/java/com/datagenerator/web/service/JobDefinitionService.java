package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobDefinitionRequest;
import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.core.schema.ConfigLoadException;
import com.datagenerator.core.schema.ConfigPathResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class JobDefinitionService {

    private static final String JOBS_DIR = "jobs";

    private final ConfigPathResolver pathResolver;

    public JobDefinitionService(ConfigPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    public List<JobDefinitionResponse> list() {
        List<JobDefinitionResponse> results = new ArrayList<>();
        for (String relativePath : pathResolver.listYamlRelativePaths(JOBS_DIR)) {
            String name = toDefinitionName(relativePath);
            String configPath = toConfigPath(relativePath);
            results.add(new JobDefinitionResponse(name, configPath, null, isReadOnly(configPath)));
        }
        return results;
    }

    public JobDefinitionResponse get(String name) {
        String configPath = toConfigPath(name);
        String content = readContent(configPath);
        return new JobDefinitionResponse(name, configPath, content, isReadOnly(configPath));
    }

    public JobDefinitionResponse create(JobDefinitionRequest request) {
        validateName(request.getName());
        validateContent(request.getContent());
        String configPath = toConfigPath(request.getName());
        if (exists(configPath)) {
            throw new IllegalArgumentException("Job definition already exists: " + request.getName());
        }
        writeContent(configPath, request.getContent());
        return new JobDefinitionResponse(request.getName(), configPath, request.getContent(), false);
    }

    public JobDefinitionResponse update(String name, JobDefinitionRequest request) {
        validateContent(request.getContent());
        String configPath = toConfigPath(name);
        if (!exists(configPath)) {
            throw new ConfigLoadException("Job definition not found: " + name);
        }
        writeContent(configPath, request.getContent());
        return new JobDefinitionResponse(name, configPath, request.getContent(), false);
    }

    public void delete(String name) {
        String configPath = toConfigPath(name);
        Path overlayFile = pathResolver.resolveOverlay(configPath);
        if (overlayFile == null || !Files.isRegularFile(overlayFile)) {
            throw new IllegalArgumentException("Built-in job definition cannot be deleted: " + name);
        }
        try {
            Files.delete(overlayFile);
        } catch (IOException exception) {
            throw new ConfigLoadException("Failed to delete job definition: " + name, exception);
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

    private boolean isReadOnly(String configPath) {
        return !pathResolver.existsOnOverlay(configPath);
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

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Job content is required");
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

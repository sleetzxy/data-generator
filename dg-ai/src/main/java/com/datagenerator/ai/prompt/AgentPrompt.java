package com.datagenerator.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 classpath {@code prompt/templates/{agentId}/} 加载提示词模板片段并拼装。
 * 自动扫描目录下所有 {@code .md} 文件，{@code system.md} 排最前，其余按文件名排序。
 */
public class AgentPrompt {

    private static final String ROOT = "prompt/templates/";
    /** 文件系统扫描失败时的回退列表 */
    private static final List<String> FALLBACK_PARTS =
            List.of("system.md", "reference.md", "overlay.md", "output-format.md");

    private final ClassLoader classLoader;

    public AgentPrompt() {
        this(AgentPrompt.class.getClassLoader());
    }

    AgentPrompt(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** 加载 agentId 目录下所有 .md 文件拼接为系统提示词。 */
    public String loadSystemPrompt(String agentId) {
        List<String> parts = discoverParts(agentId);
        StringBuilder prompt = new StringBuilder();
        for (String part : parts) {
            appendPart(prompt, agentId, part);
        }
        String result = prompt.toString().trim();
        if (result.isBlank()) {
            throw new IllegalArgumentException("No prompt templates found for agent: " + agentId);
        }
        return result;
    }

    public String loadFragment(String agentId, String fileName) {
        String content = readResource(ROOT + agentId + "/" + fileName);
        if (content == null) {
            throw new IllegalArgumentException(
                    "Prompt template not found: " + agentId + "/" + fileName);
        }
        return content.trim();
    }

    public List<String> listAgentIds() {
        List<String> agentIds = new ArrayList<>();
        try {
            var urls = classLoader.getResources("prompt/templates");
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    var dir = java.nio.file.Path.of(url.toURI());
                    if (java.nio.file.Files.isDirectory(dir)) {
                        try (var stream = java.nio.file.Files.list(dir)) {
                            stream.filter(java.nio.file.Files::isDirectory)
                                    .map(path -> path.getFileName().toString())
                                    .forEach(agentIds::add);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // classpath jar 场景下仅依赖显式配置
        }
        if (agentIds.isEmpty()) {
            agentIds.add("job-generator");
        }
        return agentIds;
    }

    /** 扫描 {@code prompt/templates/{agentId}/} 下所有 .md 文件，system.md 排最前。 */
    private List<String> discoverParts(String agentId) {
        List<String> parts = new ArrayList<>();
        try {
            var urls = classLoader.getResources(ROOT + agentId);
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        try (var stream = Files.list(dir)) {
                            stream.filter(Files::isRegularFile)
                                    .map(path -> path.getFileName().toString())
                                    .filter(name -> name.endsWith(".md"))
                                    .sorted(Comparator.comparing(
                                            name -> "system.md".equals(name) ? "" : name))
                                    .forEach(parts::add);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // JAR 场景无法列举目录，回退
        }
        if (parts.isEmpty()) {
            parts.addAll(FALLBACK_PARTS);
        }
        return parts;
    }

    private void appendPart(StringBuilder prompt, String agentId, String fileName) {
        String content = readResource(ROOT + agentId + "/" + fileName);
        if (content == null || content.isBlank()) {
            return;
        }
        if (!prompt.isEmpty()) {
            prompt.append("\n\n---\n\n");
        }
        prompt.append(content.trim());
    }

    private String readResource(String path) {
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read prompt template: " + path, exception);
        }
    }
}

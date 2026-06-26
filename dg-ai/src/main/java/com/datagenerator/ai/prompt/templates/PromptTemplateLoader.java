package com.datagenerator.ai.prompt.templates;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 classpath {@code prompt/templates/{agentId}/} 加载提示词模板片段并拼装。
 */
public class PromptTemplateLoader {

    private static final String ROOT = "prompt/templates/";
    private static final List<String> SYSTEM_PARTS =
            List.of("system.md", "reference.md", "overlay.md", "output-format.md");

    private final ClassLoader classLoader;

    public PromptTemplateLoader() {
        this(PromptTemplateLoader.class.getClassLoader());
    }

    PromptTemplateLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public String loadSystemPrompt(String agentId) {
        StringBuilder prompt = new StringBuilder();
        for (String part : SYSTEM_PARTS) {
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

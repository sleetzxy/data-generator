package com.datagenerator.web.controller;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置文档 API — 将 config-guide.md 按章节提供查询服务。
 * <p>启动时读取一次，不写缓存文件、不建索引，仅内存中按 ## 切分章节。</p>
 */
@RestController
@RequestMapping("/api/v1/docs")
public class DocsController {

    private static final Logger log = LoggerFactory.getLogger(DocsController.class);
    private static final String DOC_PATH = "static/docs/config-guide.md";

    private String fullMarkdown;
    private final List<DocSection> sections = new ArrayList<>();

    /** 文档章节 */
    public record DocSection(String title, String anchor, int startLine, int endLine) {}

    @PostConstruct
    void loadDoc() {
        try {
            ClassPathResource resource = new ClassPathResource(DOC_PATH);
            try (InputStream in = resource.getInputStream()) {
                fullMarkdown = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            parseSections(fullMarkdown);
            log.info("配置文档已加载: {} 行, {} 个章节", fullMarkdown.split("\n").length, sections.size());
        } catch (IOException e) {
            log.error("加载配置文档失败: {}", DOC_PATH, e);
            fullMarkdown = "";
        }
    }

    private void parseSections(String md) {
        sections.clear();
        String[] lines = md.split("\n");
        int currentStart = -1;
        String currentTitle = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                // 结束上一章节
                if (currentTitle != null) {
                    sections.add(new DocSection(currentTitle, toAnchor(currentTitle),
                            currentStart, i - 1));
                }
                currentTitle = line.substring(3).trim();
                currentStart = i;
            }
        }
        // 最后一个章节
        if (currentTitle != null) {
            sections.add(new DocSection(currentTitle, toAnchor(currentTitle),
                    currentStart, lines.length - 1));
        }
    }

    /** 将章节标题转为锚点（简化版，与 GitHub 风格近似） */
    private static String toAnchor(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s-]", "")
                .replaceAll("\\s+", "-");
    }

    // ==================== API 端点 ====================

    @GetMapping(value = "/config-guide", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> fullGuide() {
        return ResponseEntity.ok(fullMarkdown);
    }

    @GetMapping("/config-guide/sections")
    public ResponseEntity<List<DocSection>> listSections() {
        return ResponseEntity.ok(sections);
    }

    @GetMapping(value = "/config-guide", params = "q",
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> searchDocs(@RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(fullMarkdown);
        }
        String[] keywords = query.toLowerCase().split("\\s+");
        String[] lines = fullMarkdown.split("\n");
        StringBuilder result = new StringBuilder();

        for (DocSection sec : sections) {
            // 在章节范围内搜索关键词
            StringBuilder secText = new StringBuilder();
            for (int i = sec.startLine; i <= sec.endLine && i < lines.length; i++) {
                secText.append(lines[i]).append("\n");
            }
            String secLower = secText.toString().toLowerCase();
            boolean allMatch = true;
            for (String kw : keywords) {
                if (!secLower.contains(kw)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch && !secText.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append("\n---\n\n");
                }
                result.append("## ").append(sec.title).append("\n\n");
                result.append(secText.toString().trim());
                result.append("\n");
            }
        }

        if (result.isEmpty()) {
            return ResponseEntity.ok("未找到与「" + query + "」相关的章节。\n"
                    + "可用章节: " + sections.stream().map(DocSection::title).toList());
        }
        return ResponseEntity.ok(result.toString());
    }
}

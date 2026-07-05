package com.datagenerator.ai.tool;

import com.datagenerator.ai.client.DgWebClient;
import io.agentscope.core.tool.Tool;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识检索工具 — 从 dg-web 按需获取 config-guide.md 文档内容。
 * <p>不建本地索引、不缓存文件，每次都通过 HTTP 从 dg-web 获取最新文档。</p>
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);
    private final DgWebClient client;

    public KnowledgeTools(Optional<DgWebClient> client) {
        this.client = client.orElse(null);
    }

    private boolean hasClient() { return client != null; }

    @Tool(name = "searchDocs", description = "搜索 Data Generator 配置文档，传入关键词（如 sequence、seed、constraint、writer）返回所有相关章节的全文。不确定任何配置语法时优先调用此工具。")
    public String searchDocs(String query) {
        if (!hasClient()) return fallbackSearch(query);
        try {
            String result = client.searchDocs(query);
            if (result == null || result.isBlank()) {
                return "未找到与「" + query + "」相关的文档内容。尝试用更短的关键词搜索。";
            }
            return result;
        } catch (Exception e) {
            log.warn("searchDocs 失败: {}", query, e);
            return fallbackSearch(query);
        }
    }

    @Tool(name = "getDocSection", description = "获取配置文档的指定章节完整内容，参数为章节标题（如 选择生成策略、约束规则、Job 级 seeds）")
    public String getDocSection(String title) {
        if (!hasClient()) return fallbackSection(title);
        try {
            // 先获取章节列表匹配标题
            List<DgWebClient.DocSection> sections = client.getDocSections();
            DgWebClient.DocSection matched = null;
            for (DgWebClient.DocSection s : sections) {
                String sectionTitle = s.title();
                if (sectionTitle == null || sectionTitle.isBlank()) continue;
                if (sectionTitle.contains(title) || title.contains(sectionTitle)) {
                    matched = s;
                    break;
                }
            }
            if (matched == null) {
                return "未找到匹配的章节「" + title + "」。可用章节: "
                        + sections.stream().map(DgWebClient.DocSection::title)
                                .filter(t -> t != null && !t.isBlank())
                                .toList();
            }
            // 获取全文后截取对应章节
            String full = client.getDocFull();
            if (full == null) return "获取文档失败";
            String[] lines = full.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = matched.startLine(); i <= matched.endLine() && i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("getDocSection 失败: {}", title, e);
            return fallbackSection(title);
        }
    }

    // ==================== 降级：dg-web 不可用时返回简要提示 ====================

    private String fallbackSearch(String query) {
        return "文档服务不可用，无法搜索「" + query + "」。可用的离线知识工具: "
                + "getGeneratorGuide / getSeedGuide / getConstraintGuide / getWriterGuide / getRandomTypeGuide / getIdcardGuide。"
                + "请调用对应工具获取具体配置语法。";
    }

    private String fallbackSection(String title) {
        return "文档服务不可用，无法获取章节「" + title + "」。"
                + "请使用 getGeneratorGuide / getSeedGuide / getConstraintGuide / getWriterGuide 等工具获取具体配置语法。";
    }
}

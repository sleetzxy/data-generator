package com.datagenerator.ai.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识检索工具 — 基于 AgentScope RAG 向量语义搜索。
 * <p>文档先通过 POST /api/v1/agent/knowledge/index 索引入库，
 * 之后 Agent 可通过 searchDocs / getDocSection 进行语义检索。</p>
 */
@Component
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);
    private final Knowledge knowledge;

    public KnowledgeTools(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    /**
     * 通过 ToolEmitter 发送工具结果，触发 TOOL_RESULT_TEXT_DELTA 事件，
     * 使前端实时对话中能展示工具调用结果。
     */
    private String emitAndReturn(ToolEmitter emitter, String result) {
        if (emitter != null) {
            emitter.emit(ToolResultBlock.text(result));
        }
        return result;
    }

    @Tool(name = "searchDocs", description = "搜索 Data Generator 配置文档，传入自然语言查询（如「多数据源同时写入」「怎么控制随机范围」）返回语义最相关的章节全文。不确定任何配置语法时优先调用此工具。")
    public String searchDocs(@ToolParam(name = "query", description = "自然语言查询，如「多数据源同时写入」「sequence 生成策略怎么用」") String query,
            ToolEmitter emitter) {
        try {
            List<Document> docs = knowledge.retrieve(query,
                    RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build()
            ).block();

            if (docs == null || docs.isEmpty()) {
                return emitAndReturn(emitter, "未找到与「" + query + "」语义相关的文档内容。请尝试换一种表述方式，或确认知识库已索引。");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                if (i > 0) sb.append("\n---\n\n");
                sb.append(doc.getMetadata().getContentText());
            }
            return emitAndReturn(emitter, sb.toString());
        } catch (Exception e) {
            log.warn("searchDocs 检索失败: {}", query, e);
            return emitAndReturn(emitter, "知识库检索失败: " + e.getMessage() + "。请基于已有的领域知识继续完成任务。");
        }
    }

    @Tool(name = "getDocSection", description = "按章节标题语义搜索，获取配置文档的指定章节完整内容（如「选择生成策略」「约束规则」「Job 级 seeds」）")
    public String getDocSection(@ToolParam(name = "title", description = "章节标题或主题描述，如「选择生成策略」「约束规则」「Job 级 seeds」") String title,
            ToolEmitter emitter) {
        try {
            List<Document> docs = knowledge.retrieve(title,
                    RetrieveConfig.builder().limit(1).scoreThreshold(0.5).build()
            ).block();

            if (docs == null || docs.isEmpty()) {
                return emitAndReturn(emitter, "未找到匹配「" + title + "」的章节。请尝试更具体的章节标题。");
            }
            return emitAndReturn(emitter, docs.get(0).getMetadata().getContentText());
        } catch (Exception e) {
            log.warn("getDocSection 检索失败: {}", title, e);
            return emitAndReturn(emitter, "知识库检索失败: " + e.getMessage() + "。请基于已有的领域知识继续完成任务。");
        }
    }
}

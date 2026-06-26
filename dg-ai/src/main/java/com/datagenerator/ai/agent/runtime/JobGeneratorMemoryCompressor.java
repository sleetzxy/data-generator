package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.agent.result.DraftResultParser;
import com.datagenerator.ai.application.session.TurnContinueMode;
import com.datagenerator.ai.memory.ChatMemoryContentCompressor;
import com.datagenerator.ai.tool.impl.model.DgWebModels.PreviewResult;
import com.datagenerator.ai.tool.impl.model.DgWebModels.SchemaDetail;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JobGenerator Agent 专属的记忆内容压缩策略。
 * <p>将对话记忆中的大段结构化 JSON / YAML 替换为摘要；完整内容存于会话草稿或参考缓存。
 */
public class JobGeneratorMemoryCompressor implements ChatMemoryContentCompressor {

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```json\\s*\\n[\\s\\S]*?```", Pattern.CASE_INSENSITIVE);
    private static final Pattern YAML_FENCE = Pattern.compile(
            "```(?:yaml|yml)[\\s\\S]*?```", Pattern.CASE_INSENSITIVE);
    /** 仅匹配 2 空格缩进的 {@code - name:}，即 {@code tables:} 的直接子项，避免误计列/生成器级别的 name */
    static final Pattern TABLE_NAME = Pattern.compile(
            "^  -\\s+name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    static final Set<String> TOOL_RESULTS_NEVER_COMPRESS = Set.of(
            "listConnections",
            "listJobDefinitions",
            "listSchemas",
            "validateJobYaml",
            "validateDraftJobYaml",
            "getJobDefinitionSchedule",
            "listSubmittedJobs",
            "getSubmittedJob",
            "getSubmittedJobLogs",
            "cancelSubmittedJob",
            "deleteJobDefinition",
            "saveDraftJobDefinition",
            "copyJobYamlToDraft",
            "createJobDefinition",
            "updateJobDefinition",
            "submitJob",
            "submitDraftJob");

    public JobGeneratorMemoryCompressor() {
    }

    // ==================== ChatMemoryContentCompressor 接口实现 ====================

    @Override
    public String compressConversationText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return replaceYamlFences(replaceJsonBlocks(text));
    }

    @Override
    public String compressToolResult(String toolName, String result, int toolResultMaxChars) {
        if (result == null || result.isBlank()) {
            return result;
        }
        if (TOOL_RESULTS_NEVER_COMPRESS.contains(toolName)) {
            return result;
        }
        if (result.length() <= toolResultMaxChars) {
            return result;
        }
        if (toolName.startsWith("preview")) {
            return "["
                    + toolName
                    + " 结果约 "
                    + result.length()
                    + " 字符，预览明细未写入对话记忆]";
        }
        if ("getJobYaml".equals(toolName) || "getSchema".equals(toolName)) {
            return result;
        }
        return "["
                + toolName
                + " 返回约 "
                + result.length()
                + " 字符，明细未写入对话记忆；请缩小查询范围或分批调用]";
    }

    // ==================== JobGenerator 专属摘要方法 ====================

    /** 将草稿 YAML 替换为行数 + 表数摘要注释 */
    public String summarizeDraftStored(String yaml, boolean incomplete) {
        int lines = countLines(yaml);
        String tableHint = summarizeTables(yaml);
        String suffix = tableHint.isBlank() ? "" : tableHint.replace("，约 ", ",");
        if (incomplete) {
            return "<!-- dg-draft:" + lines + "行" + suffix + ",生成中 -->";
        }
        return "<!-- dg-draft:" + lines + "行" + suffix + " -->";
    }

    /** 将参考 Job 的完整 YAML 替换为文件名 + 行数 + 表数摘要 */
    public String summarizeReferenceJob(String fileName, String yaml) {
        int lines = countLines(yaml);
        String tableHint = summarizeTables(yaml);
        String suffix = tableHint.isBlank() ? "" : tableHint.replace("，约 ", ",");
        return "<!-- dg-ref:" + fileName + "," + lines + "行" + suffix + " -->";
    }

    /** 将 Schema 详情替换为字段摘要，完整结构缓存于会话 */
    public String summarizeSchema(String name, SchemaDetail detail) {
        if (detail == null) {
            return "[Schema " + name + " 未找到]";
        }
        int fieldCount = detail.fields() != null ? detail.fields().size() : 0;
        String fieldPreview = detail.fields() == null
                ? ""
                : detail.fields().stream()
                        .limit(12)
                        .map(field -> field.name() + ":" + field.type())
                        .collect(Collectors.joining(", "));
        if (fieldCount > 12) {
            fieldPreview = fieldPreview + ", …";
        }
        return "[Schema「"
                + name
                + "」已载入会话缓存，表 "
                + detail.table()
                + "，"
                + fieldCount
                + " 个字段"
                + (fieldPreview.isBlank() ? "" : "（" + fieldPreview + "）")
                + "]";
    }

    /** 将预览结果替换为状态摘要 */
    public String summarizePreviewResult(PreviewResult result) {
        if (result == null) {
            return "[预览无结果]";
        }
        String tables = result.tables() == null
                ? ""
                : result.tables().stream()
                        .map(table -> table.name() + "(" + table.rowCount() + "行)")
                        .collect(Collectors.joining(", "));
        return "[预览完成 status="
                + result.status()
                + "，耗时 "
                + result.duration()
                + (tables.isBlank() ? "" : "，表：" + tables)
                + "]";
    }

    // ==================== 私有辅助方法 ====================

    private String replaceJsonBlocks(String text) {
        Matcher matcher = JSON_BLOCK.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String block = matcher.group();
            DraftResultParser.MergeResult merge =
                    DraftResultParser.merge(block, null, TurnContinueMode.NONE);
            String replacement = merge.merged()
                    .map(result -> summarizeDraftStored(result.draftYaml(), merge.incomplete()))
                    .orElseGet(() -> fallbackJsonBlockReplacement(block));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** JSON 无 draftYaml 或解析失败时，保留 message；否则用 HTML 注释占位（不面向用户展示） */
    private String fallbackJsonBlockReplacement(String jsonBlock) {
        String message = DraftResultParser.extractPartialMessage(jsonBlock);
        if (message != null && !message.isBlank()) {
            return message;
        }
        return "<!-- dg-structured:compressed -->";
    }

    private String replaceYamlFences(String text) {
        Matcher matcher = YAML_FENCE.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String fence = matcher.group();
            String inner = fence.replaceAll("^```(?:yaml|yml)\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();
            matcher.appendReplacement(
                    buffer, Matcher.quoteReplacement(summarizeDraftStored(inner, false)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static int countLines(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        return yaml.split("\\R", -1).length;
    }

    /**
     * 统计 YAML 中 {@code tables:} 直接子级的表数量。
     * <p>仅匹配 2 空格缩进的 {@code - name:} 行，避免误计列定义和生成器定义中的 name。
     */
    static String summarizeTables(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "";
        }
        Matcher matcher = TABLE_NAME.matcher(yaml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count == 0) {
            return "";
        }
        return "，约 " + count + " 张表";
    }
}

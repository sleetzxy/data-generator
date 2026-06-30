package com.datagenerator.ai.agent.result;

import com.datagenerator.ai.agent.runtime.JobSessionState;
import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.application.sse.SseEvent;
import com.datagenerator.ai.tool.model.DgWebModels.PreviewResult;
import com.datagenerator.ai.tool.model.DgWebModels.SchemaDetail;
import com.datagenerator.ai.tool.model.DgWebModels.ValidationResult;
import com.datagenerator.ai.tool.web.DataGeneratorWebClient;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Job Generator Agent 结果处理：草稿 YAML 提取/校验 + 产物摘要格式化。 */
public class JobResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobResultProcessor.class);
    private static final String PARSE_ERROR_MARKER = "Failed to parse YAML content";
    private static final Pattern YAML_FENCE =
            Pattern.compile("```(?:yaml|yml)\\s*\\R([\\s\\S]*?)\\R```", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_FENCE = Pattern.compile("```\\s*\\R([\\s\\S]*?)\\R```");
    private static final Pattern TABLE_NAME = Pattern.compile(
            "^  -\\s+name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final String TABLE_HINT_PREFIX = "，约 ";

    private final DataGeneratorWebClient webClient;

    public JobResultProcessor(DataGeneratorWebClient webClient) {
        this.webClient = webClient;
    }

    // ── 草稿处理（原 DraftResultProcessor） ──

    /** 处理 Agent 最终响应：提取草稿 YAML、校验、更新会话状态。 */
    public boolean process(AgentSession session, ChatResponse response, Consumer<SseEvent> emitter) {
        String fullText = response.aiMessage() != null ? response.aiMessage().text() : "";
        var extractedYaml = extractYaml(fullText);
        if (extractedYaml.isEmpty()) {
            // 尝试提取未闭合 YAML 代码块（响应被截断时闭合 ``` 缺失）
            var truncated = extractUnclosedYaml(fullText);
            if (truncated.isPresent()) {
                String yaml = truncated.get();
                JobSessionState.setDraftYaml(session, yaml);
                JobSessionState.setDraftIncomplete(session, true);
                JobSessionState.setDraftValidated(session, false);
                log.warn("Draft YAML appears truncated for session {} ({} chars), marking incomplete",
                        session.getSessionId(), yaml.length());
                emitter.accept(SseEvent.event("validation_error",
                        Map.of("errors", List.of("YAML 输出被截断，请说「继续」来完成剩余部分"))));
                return false;
            }
            log.debug("No YAML extracted for session {}", session.getSessionId());
            return false;
        }

        String yaml = extractedYaml.get();
        JobSessionState.setDraftYaml(session, yaml);
        int draftChars = yaml.length();
        int tableCount = countTables(yaml);
        ValidationResult result = webClient.validateYaml(yaml);
        if (result.valid()) {
            JobSessionState.setDraftIncomplete(session, false);
            JobSessionState.setDraftValidated(session, true);
            logValidatedDraft(session, yaml);
        } else if (isParseFailure(result)) {
            JobSessionState.setDraftIncomplete(session, true);
            JobSessionState.setDraftValidated(session, false);
            log.warn(
                    "Draft YAML parse failed for session {} ({} tables, {} chars): {}",
                    session.getSessionId(), tableCount, draftChars, result.errors());
            emitter.accept(SseEvent.event("validation_error", Map.of("errors", result.errors())));
        } else {
            JobSessionState.setDraftIncomplete(session, false);
            JobSessionState.setDraftValidated(session, false);
            log.warn(
                    "Draft YAML validation failed for session {}: {}",
                    session.getSessionId(), result.errors());
            emitter.accept(SseEvent.event("validation_error", Map.of("errors", result.errors())));
        }

        log.debug("Draft processed for session {}: incomplete={}, validated={}, chars={}",
                session.getSessionId(),
                JobSessionState.isDraftIncomplete(session),
                JobSessionState.isDraftValidated(session),
                yaml.length());
        return false;
    }

    private void logValidatedDraft(AgentSession session, String yaml) {
        log.info("Validated draft ready for session {} ({})",
                session.getSessionId(), formatStats(yaml));
    }

    private static boolean isParseFailure(ValidationResult result) {
        if (result == null || result.valid() || result.errors() == null || result.errors().isEmpty()) {
            return false;
        }
        return result.errors().stream()
                .anyMatch(error -> error != null && error.contains(PARSE_ERROR_MARKER));
    }

    // ── 产物摘要格式化（原 JobArtifactSummaryFormatter） ──

    /** 生成草稿存储注释。 */
    public String summarizeDraftStored(String yaml, boolean incomplete) {
        int lines = countLines(yaml);
        String tableHint = summarizeTables(yaml);
        String suffix = tableHint.isBlank() ? "" : tableHint.replace(TABLE_HINT_PREFIX, ",");
        if (incomplete) {
            return "<!-- dg-draft:" + lines + "行" + suffix + ",生成中 -->";
        }
        return "<!-- dg-draft:" + lines + "行" + suffix + " -->";
    }

    /** 生成参考 Job 注释。 */
    public String summarizeReferenceJob(String fileName, String yaml) {
        int lines = countLines(yaml);
        String tableHint = summarizeTables(yaml);
        String suffix = tableHint.isBlank() ? "" : tableHint.replace(TABLE_HINT_PREFIX, ",");
        return "<!-- dg-ref:" + fileName + "," + lines + "行" + suffix + " -->";
    }

    /** 格式化 Schema 摘要。 */
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
                + name + "」已载入会话缓存，表 "
                + detail.table() + "，"
                + fieldCount + " 个字段"
                + (fieldPreview.isBlank() ? "" : "（" + fieldPreview + "）")
                + "]";
    }

    /** 格式化预览结果摘要。 */
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
                + result.status() + "，耗时 " + result.duration()
                + (tables.isBlank() ? "" : "，表：" + tables)
                + "]";
    }

    // ── 统计工具方法 ──

    /** 统计 YAML 中 tables 数量。 */
    public static int countTables(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        Matcher matcher = TABLE_NAME.matcher(yaml);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 统计 YAML 行数。 */
    public static int countLines(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        return yaml.split("\\R", -1).length;
    }

    /** 格式化 YAML 统计信息。 */
    public static String formatStats(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "空草稿";
        }
        int tables = countTables(yaml);
        String tableHint = tables > 0 ? " / 约 " + tables + " 张表" : "";
        return "约 " + countLines(yaml) + " 行 / " + yaml.length() + " 字符" + tableHint;
    }

    static String summarizeTables(String yaml) {
        int count = countTables(yaml);
        if (count == 0) {
            return "";
        }
        return TABLE_HINT_PREFIX + count + " 张表";
    }

    // ── YAML 提取（私有辅助） ──

    private static Optional<String> extractYaml(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String yaml = findLast(YAML_FENCE, text, true);
        if (yaml != null) {
            return Optional.of(yaml);
        }
        String generic = findLast(GENERIC_FENCE, text, false);
        if (generic != null && looksLikeJobYaml(generic)) {
            return Optional.of(generic);
        }
        return Optional.empty();
    }

    /** 尝试提取最后一个未闭合的 YAML 代码块（响应截断时闭合 ``` 缺失）。 */
    private static Optional<String> extractUnclosedYaml(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        int yamlIdx = text.lastIndexOf("```yaml");
        int ymlIdx = text.lastIndexOf("```yml");
        int fenceIdx = Math.max(yamlIdx, ymlIdx);
        if (fenceIdx < 0) {
            fenceIdx = text.lastIndexOf("```");
            if (fenceIdx < 0) {
                return Optional.empty();
            }
        }
        int contentStart = text.indexOf('\n', fenceIdx);
        if (contentStart < 0) {
            return Optional.empty();
        }
        String content = text.substring(contentStart + 1);
        if (content.contains("\n```")) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    private static String findLast(Pattern pattern, String text, boolean acceptAnyNonBlank) {
        Matcher matcher = pattern.matcher(text);
        String latest = null;
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block == null || block.isBlank()) {
                continue;
            }
            String trimmed = block.trim();
            if (acceptAnyNonBlank || looksLikeJobYaml(trimmed)) {
                latest = trimmed;
            }
        }
        return latest;
    }

    private static boolean looksLikeJobYaml(String yaml) {
        return yaml.contains("tables:")
                || yaml.contains("writer:")
                || yaml.contains("writers:")
                || yaml.contains("\n- name:")
                || yaml.contains("\n  - name:");
    }
}

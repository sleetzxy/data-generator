package com.datagenerator.ai.agent.result;

import com.datagenerator.ai.application.session.TurnContinueMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从模型回复中解析 {@link DraftResult} 并与会话草稿合并。 */
public final class DraftResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_FENCE =
            Pattern.compile("```json\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_JSON_FENCE =
            Pattern.compile("```json\\s*\\n([\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    private DraftResultParser() {
    }

    public record MergeResult(Optional<DraftResult> merged, boolean incomplete) {
        public static MergeResult empty() {
            return new MergeResult(Optional.empty(), false);
        }
    }

    public static MergeResult merge(
            String text, String existingDraftYaml, TurnContinueMode continueMode) {
        if (text == null || text.isBlank()) {
            if (existingDraftYaml != null && !existingDraftYaml.isBlank()) {
                return new MergeResult(
                        Optional.of(new DraftResult("", existingDraftYaml.trim(), true)),
                        false);
            }
            return MergeResult.empty();
        }

        ParsedBlock closed = parseClosedJsonBlock(text);
        if (closed != null) {
            return mergeParsed(closed.result(), existingDraftYaml, continueMode, false);
        }

        ParsedBlock open = parseOpenJsonBlock(text);
        if (open != null) {
            return mergeParsed(open.result(), existingDraftYaml, TurnContinueMode.APPEND, true);
        }

        if (existingDraftYaml != null && !existingDraftYaml.isBlank()) {
            return new MergeResult(
                    Optional.of(new DraftResult("", existingDraftYaml.trim(), true)),
                    false);
        }
        return MergeResult.empty();
    }

    private static MergeResult mergeParsed(
            DraftResult parsed,
            String existingDraftYaml,
            TurnContinueMode continueMode,
            boolean forceIncomplete) {
        String mergedYaml = mergeDraftYaml(existingDraftYaml, parsed.draftYaml(), continueMode);
        boolean incomplete = forceIncomplete || !parsed.draftComplete();
        DraftResult merged = new DraftResult(
                parsed.message(), mergedYaml, !incomplete && parsed.draftComplete());
        if (mergedYaml.isBlank()) {
            return new MergeResult(Optional.empty(), incomplete);
        }
        return new MergeResult(Optional.of(merged), incomplete);
    }

    private static String mergeDraftYaml(
            String existingDraftYaml, String segmentYaml, TurnContinueMode continueMode) {
        if (segmentYaml == null || segmentYaml.isBlank()) {
            return existingDraftYaml != null ? existingDraftYaml.trim() : "";
        }
        if (continueMode == TurnContinueMode.REPAIR
                || existingDraftYaml == null
                || existingDraftYaml.isBlank()) {
            return segmentYaml.trim();
        }
        if (continueMode == TurnContinueMode.APPEND) {
            return appendYaml(existingDraftYaml.trim(), segmentYaml.trim());
        }
        // NONE：已有草稿时，仅当 JSON 片段含 Job 结构才覆盖，避免占位符 wipe 工具 copy 的草稿
        if (!containsJobStructure(segmentYaml)) {
            return existingDraftYaml.trim();
        }
        return segmentYaml.trim();
    }

    /** 判断 YAML 片段是否像 Job 正文（含 tables / writer 等），而非占位说明。 */
    static boolean containsJobStructure(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return false;
        }
        return yaml.contains("tables:")
                || yaml.contains("writer:")
                || yaml.contains("writers:")
                || yaml.contains("\n- name:")
                || yaml.contains("\n  - name:");
    }

    private static String appendYaml(String base, String segment) {
        if (base.endsWith("\n")) {
            return base + segment;
        }
        return base + "\n" + segment;
    }

    private static ParsedBlock parseClosedJsonBlock(String text) {
        Matcher matcher = JSON_FENCE.matcher(text);
        DraftResult last = null;
        while (matcher.find()) {
            DraftResult parsed = parseJson(matcher.group(1));
            if (parsed != null) {
                last = parsed;
            }
        }
        return last != null ? new ParsedBlock(last) : null;
    }

    private static ParsedBlock parseOpenJsonBlock(String text) {
        Matcher matcher = OPEN_JSON_FENCE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        DraftResult parsed = parsePartialJson(matcher.group(1));
        return parsed != null ? new ParsedBlock(parsed) : null;
    }

    private static DraftResult parseJson(String json) {
        try {
            return MAPPER.readValue(json.trim(), DraftResult.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static DraftResult parsePartialJson(String jsonFragment) {
        DraftResult parsed = parseJson(jsonFragment);
        if (parsed != null) {
            return new DraftResult(parsed.message(), parsed.draftYaml(), false);
        }
        String draftYaml = extractPartialDraftYaml(jsonFragment);
        if (draftYaml == null || draftYaml.isBlank()) {
            return null;
        }
        return new DraftResult("", draftYaml, false);
    }

    /** 从含 {@code ```json} 围栏的文本中提取 {@code message} 字段（可不完整）。 */
    public static String extractPartialMessage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int fence = indexOfIgnoreCase(text, "```json");
        if (fence < 0) {
            return null;
        }
        int key = text.indexOf("\"message\"", fence);
        if (key < 0) {
            return null;
        }
        int colon = text.indexOf(':', key + 9);
        if (colon < 0) {
            return null;
        }
        int startQuote = findJsonStringStart(text, colon + 1);
        if (startQuote < 0) {
            return null;
        }
        return readJsonStringContent(text, startQuote + 1);
    }

    private static String extractPartialDraftYaml(String jsonFragment) {
        int keyIndex = jsonFragment.indexOf("\"draftYaml\"");
        if (keyIndex < 0) {
            return null;
        }
        int colon = jsonFragment.indexOf(':', keyIndex);
        if (colon < 0) {
            return null;
        }
        int startQuote = jsonFragment.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < jsonFragment.length(); i++) {
            char ch = jsonFragment.charAt(i);
            if (escaped) {
                builder.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                break;
            }
            builder.append(ch);
        }
        return builder.toString()
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .trim();
    }

    private static int findJsonStringStart(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch == '"') {
                return i;
            }
            return -1;
        }
        return -1;
    }

    private static String readJsonStringContent(String text, int start) {
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escape) {
                out.append(decodeJsonEscape(ch));
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') {
                break;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static char decodeJsonEscape(char ch) {
        return switch (ch) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> ch;
        };
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private record ParsedBlock(DraftResult result) {
    }
}

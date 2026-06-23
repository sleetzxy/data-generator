package com.datagenerator.ai.artifact;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Agent 回复中提取 generate-job skill 约定的 YAML artifact 标记块。
 */
public final class YamlArtifactExtractor {

    private static final Pattern MARKER = Pattern.compile(
            "<!-- dg-artifact:yaml -->(\\s*\\n?)([\\s\\S]*?)(\\s*\\n?)<!-- /dg-artifact -->");

    private static final Pattern YAML_FENCE = Pattern.compile(
            "```(?:yaml|yml)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private YamlArtifactExtractor() {
    }

    public static Optional<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher marker = MARKER.matcher(text);
        if (marker.find()) {
            return Optional.of(marker.group(2).trim());
        }
        Matcher fence = YAML_FENCE.matcher(text);
        if (fence.find()) {
            return Optional.of(fence.group(1).trim());
        }
        return Optional.empty();
    }
}

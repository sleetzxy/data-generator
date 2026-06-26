package com.datagenerator.ai.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 会话草稿 YAML 体量统计。 */
public final class DraftYamlMetrics {

    private static final Pattern TABLE_NAME = Pattern.compile(
            "^\\s+-\\s+name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    private DraftYamlMetrics() {
    }

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

    public static int countLines(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return 0;
        }
        return yaml.split("\\R", -1).length;
    }

    public static String formatStats(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "空草稿";
        }
        int tables = countTables(yaml);
        String tableHint = tables > 0 ? " / 约 " + tables + " 张表" : "";
        return "约 " + countLines(yaml) + " 行 / " + yaml.length() + " 字符" + tableHint;
    }
}
